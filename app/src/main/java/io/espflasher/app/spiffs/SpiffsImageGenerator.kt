package io.espflasher.app.spiffs

import io.espflasher.app.util.BinaryUtils

/**
 * Builds a SPIFFS filesystem image in memory - the same job ESP-IDF's
 * `spiffsgen.py` does on a desktop - so a SPIFFS partition can be generated
 * and flashed entirely on-device, with no Python involved.
 *
 * ============================================================================
 * VALIDATION STATUS
 * ============================================================================
 * This is a direct, structure-for-structure port of a real, working
 * `spiffsgen.py` (confirmed on real ESP32 hardware: generated there, flashed
 * with stock esptool, mounted and served files correctly) - not a
 * from-memory reconstruction like the first version of this file was. Every
 * class below (LuPage/IndexPage/DataPage/Block/Fs) mirrors that script's
 * SpiffsObjLuPage/SpiffsObjIndexPage/SpiffsObjDataPage/SpiffsBlock/SpiffsFS
 * one-for-one, including the exact page-header alignment padding, the 2-byte
 * (not 4-byte) data-page references, the index-page-can-span-blocks
 * allocation behavior, and the per-block magic-footer scheme - all things
 * the previous version of this file got wrong or omitted entirely, which is
 * exactly why it produced images that failed to mount.
 *
 * Still worth a byte-diff against a fresh spiffsgen.py run before you fully
 * trust it in a new configuration (different page/block size, very large
 * file counts) - this port is faithful to the reference algorithm, but only
 * hardware can confirm a given output actually mounts.
 * ============================================================================
 *
 * @param imageSize total size of the SPIFFS partition in bytes (must be a
 *   whole number of [blockSize]).
 * @param pageSize physical page size in bytes - must match what the
 *   firmware mounts with (ESP-IDF default 256).
 * @param blockSize erase-block size in bytes, must be a multiple of
 *   [pageSize] - must match what the firmware mounts with (ESP-IDF default
 *   4096; some Arduino-ESP32 boards use 8192, check before assuming).
 */
class SpiffsImageGenerator(
    private val imageSize: Long,
    private val pageSize: Int = 256,
    private val blockSize: Int = 4096
) {
    companion object {
        private const val OBJ_ID_LEN = 2 // SPIFFS_OBJ_ID_LEN - object ids and page refs are both 16-bit
        private const val OBJ_NAME_LEN = 32 // fixed, matches CONFIG_SPIFFS_OBJ_NAME_LEN default
        private const val META_LEN = 4 // fixed, matches CONFIG_SPIFFS_META_LENGTH default
        private const val PH_FLAG_USED_FINAL = 0xFC // data page: USED+FINAL cleared, INDEX bit left set
        private const val PH_FLAG_USED_FINAL_INDEX = 0xF8 // index page: USED+FINAL+INDEX all cleared
        private const val TYPE_FILE = 1
        private const val IX_FLAG = 0x8000 // bit 15 of a 16-bit obj_id marks an index page/slot
        private const val MAGIC_BASE = 0x20140529
    }

    // ---- geometry (mirrors SpiffsBuildConfig) ----
    private val pagesPerBlock = blockSize / pageSize
    private val objLuPagesPerBlock = ((pagesPerBlock * OBJ_ID_LEN) + pageSize - 1) / pageSize
    private val objUsablePagesPerBlock = pagesPerBlock - objLuPagesPerBlock
    private val objLuPagesObjIdsLim = pageSize / OBJ_ID_LEN
    private val objDataPageHeaderLen = OBJ_ID_LEN + 2 /* span_ix */ + 1 /* flags */ // = 5
    private val objDataPageHeaderAlignedPad = (4 - (objDataPageHeaderLen % 4)).let { if (it == 4) 0 else it }
    private val objDataPageHeaderAligned = objDataPageHeaderLen + objDataPageHeaderAlignedPad // = 8
    private val objDataPageContentLen = pageSize - objDataPageHeaderLen // NOT the aligned value - data pages have no pad
    private val objIndexPagesHeaderLen = objDataPageHeaderAligned + 4 /* size */ + 1 /* type */ + OBJ_NAME_LEN + META_LEN // = 49
    private val objIndexPagesObjIdsHeadLim = (pageSize - objIndexPagesHeaderLen) / OBJ_ID_LEN // refs on the span_ix==0 page
    private val objIndexPagesObjIdsLim = (pageSize - objDataPageHeaderAligned) / OBJ_ID_LEN // refs on continuation pages

    init {
        require(blockSize % pageSize == 0) { "blockSize ($blockSize) must be a multiple of pageSize ($pageSize)" }
        require(imageSize % blockSize == 0L) { "imageSize ($imageSize) must be a multiple of blockSize ($blockSize)" }
        require(objLuPagesPerBlock < pagesPerBlock) {
            "blockSize/pageSize combination leaves no room for data - " +
                "$objLuPagesPerBlock of $pagesPerBlock pages per block would be lookup-table overhead"
        }
        require(objIndexPagesObjIdsHeadLim > 0) {
            "pageSize ($pageSize) is too small to fit even one data-page reference alongside the object index header"
        }
    }

    // ---- page abstractions (mirror SpiffsObjLuPage / SpiffsObjIndexPage / SpiffsObjDataPage) ----

    private abstract class Page

    /** obj_ids: list of (value, isIndexPageSlot) in registration order - matches Python's (obj_id, page_type) tuples. */
    private inner class LuPage(val bix: Int) : Page() {
        var objIdsLimit = objLuPagesObjIdsLim
        val objIds = mutableListOf<Pair<Int, Boolean>>() // (obj_id, isIndex)

        fun registerSlot(objId: Int, isIndex: Boolean) {
            if (objIdsLimit <= 0) throw SpiffsFullError()
            objIds += objId to isIndex
            objIdsLimit--
        }

        /** Port of SpiffsObjLuPage.magicfy(blocks_lim). Only runs if >=2 slots are still free. */
        fun magicfy(blocksLim: Int) {
            val remaining = objIdsLimit
            if (remaining < 2) return
            for (i in 0 until remaining) {
                if (i == remaining - 2) {
                    val magic = calcMagic(bix, blocksLim)
                    objIds += magic to false // stored as a plain (non-index) slot value, no IX_FLAG
                    objIdsLimit--
                    break
                } else {
                    objIds += 0xFFFF to false
                    objIdsLimit--
                }
            }
        }

        fun toBinary(): ByteArray {
            val out = java.io.ByteArrayOutputStream(pageSize)
            for ((objId, isIndex) in objIds) {
                val value = if (isIndex) objId or IX_FLAG else objId
                out.write(BinaryUtils.u16le(value))
            }
            padWithFF(out, pageSize)
            return out.toByteArray()
        }
    }

    private inner class IndexPage(val objId: Int, val spanIx: Int, val size: Long, val name: String) : Page() {
        var pagesLim = if (spanIx == 0) objIndexPagesObjIdsHeadLim else objIndexPagesObjIdsLim
        val pageRefs = mutableListOf<Long>() // absolute byte offsets of the data pages this indexes

        fun registerDataPage(byteOffset: Long) {
            if (pagesLim <= 0) throw SpiffsFullError()
            pageRefs += byteOffset
            pagesLim--
        }

        fun toBinary(): ByteArray {
            val out = java.io.ByteArrayOutputStream(pageSize)
            out.write(BinaryUtils.u16le(objId or IX_FLAG))
            out.write(BinaryUtils.u16le(spanIx))
            out.write(PH_FLAG_USED_FINAL_INDEX)
            repeat(objDataPageHeaderAlignedPad) { out.write(0xFF) }

            if (spanIx == 0) {
                out.write(BinaryUtils.u32le(size))
                out.write(TYPE_FILE)
                val nameBytes = name.toByteArray(Charsets.US_ASCII)
                require(nameBytes.size < OBJ_NAME_LEN) {
                    "\"$name\" is ${nameBytes.size} bytes - names (including the path) must be under $OBJ_NAME_LEN bytes " +
                        "(SPIFFS has no real directory tree, so the whole path counts against this limit)"
                }
                out.write(nameBytes)
                repeat(OBJ_NAME_LEN - nameBytes.size) { out.write(0) } // NUL-pad name to fixed width
                repeat(META_LEN) { out.write(0) } // no real metadata to store
            }

            val pageShift = Integer.numberOfTrailingZeros(pageSize) // log2(pageSize); pageSize is required to be a power of 2 by SPIFFS itself
            for (offset in pageRefs) {
                out.write(BinaryUtils.u16le((offset shr pageShift).toInt()))
            }
            padWithFF(out, pageSize)
            return out.toByteArray()
        }
    }

    private class DataPage(val offset: Long, val objId: Int, val spanIx: Int, val contents: ByteArray) : Page() {
        fun toBinary(pageSize: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(pageSize)
            out.write(BinaryUtils.u16le(objId))
            out.write(BinaryUtils.u16le(spanIx))
            out.write(PH_FLAG_USED_FINAL)
            out.write(contents)
            padWithFF(out, pageSize)
            return out.toByteArray()
        }
    }

    // ---- block (mirrors SpiffsBlock) ----

    private inner class Block(val bix: Int) {
        val offset = bix.toLong() * blockSize
        var remainingPages = objUsablePagesPerBlock
        val pages = mutableListOf<Page>()
        val luPages: List<LuPage>
        var luPageCursor = 0

        var curObjId = 0
        var curObjIndexSpanIx = 0
        var curObjDataSpanIx = 0
        var curObjIdxPage: IndexPage? = null

        init {
            luPages = (0 until objLuPagesPerBlock).map { LuPage(bix) }
            pages.addAll(luPages)
        }

        fun isFull() = remainingPages <= 0

        private fun registerInLu(objId: Int, isIndex: Boolean) {
            while (true) {
                try {
                    luPages[luPageCursor].registerSlot(objId, isIndex)
                    return
                } catch (e: SpiffsFullError) {
                    luPageCursor++
                    if (luPageCursor >= luPages.size) throw e
                }
            }
        }

        fun beginObj(objId: Int, size: Long, name: String, indexSpanIx: Int = 0, dataSpanIx: Int = 0) {
            if (remainingPages <= 0) throw BlockFullError()
            curObjId = objId
            curObjIndexSpanIx = indexSpanIx
            curObjDataSpanIx = dataSpanIx
            val page = IndexPage(objId, curObjIndexSpanIx, size, name)
            registerInLu(objId, isIndex = true)
            pages += page
            curObjIdxPage = page
            remainingPages--
            curObjIndexSpanIx++
        }

        /** @throws SpiffsFullError if the current index page is out of ref capacity (caller starts a continuation page). */
        fun updateObj(contents: ByteArray) {
            if (remainingPages <= 0) throw BlockFullError()
            val dataOffset = offset + pages.size.toLong() * pageSize
            val page = DataPage(dataOffset, curObjId, curObjDataSpanIx, contents)
            curObjIdxPage!!.registerDataPage(dataOffset) // throws SpiffsFullError if this index page is full - NOT a block-full condition
            registerInLu(curObjId, isIndex = false)
            pages += page
            curObjDataSpanIx++
            remainingPages--
        }

        fun endObj() {
            curObjIdxPage = null
        }

        fun toBinary(blocksLim: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(blockSize)
            for ((idx, page) in pages.withIndex()) {
                if (idx == objLuPagesPerBlock - 1) (page as LuPage).magicfy(blocksLim)
                out.write(
                    when (page) {
                        is LuPage -> page.toBinary()
                        is IndexPage -> page.toBinary()
                        is DataPage -> page.toBinary(pageSize)
                        else -> throw IllegalStateException("unknown page type")
                    }
                )
            }
            padWithFF(out, blockSize)
            return out.toByteArray()
        }
    }

    // ---- filesystem driver (mirrors SpiffsFS) ----

    private val blocksLim = (imageSize / blockSize).toInt()
    private val blocks = mutableListOf<Block>()
    private var remainingBlocks = blocksLim
    private var nextObjId = 1

    private fun isFull() = remainingBlocks <= 0

    private fun createBlock(): Block {
        if (isFull()) throw SpiffsFullError()
        val block = Block(blocks.size)
        blocks += block
        remainingBlocks--
        return block
    }

    /**
     * @param files relative path (a leading "/" is added automatically if
     *   missing, matching `spiffsgen.py`'s own convention) plus raw content.
     * @return the full [imageSize]-byte image, ready to flash at the
     *   partition's offset.
     */
    fun generate(files: List<SpiffsSourceFile>): ByteArray {
        blocks.clear()
        remainingBlocks = blocksLim
        nextObjId = 1

        for (file in files) {
            val name = if (file.path.startsWith("/")) file.path else "/${file.path}"
            createFile(name, file.content)
        }

        val allBlockBytes = mutableListOf<ByteArray>()
        for (block in blocks) allBlockBytes += block.toBinary(blocksLim)
        var bix = blocks.size
        while (remainingBlocks > 0) {
            allBlockBytes += Block(bix).toBinary(blocksLim) // an all-empty block still gets its magic footer
            remainingBlocks--
            bix++
        }

        val out = java.io.ByteArrayOutputStream(imageSize.toInt())
        for (b in allBlockBytes) out.write(b)
        return out.toByteArray()
    }

    private fun createFile(name: String, contents: ByteArray) {
        val objId = nextObjId
        var block = blocks.lastOrNull()
        if (block == null) {
            block = createBlock()
            block.beginObj(objId, contents.size.toLong(), name)
        } else {
            try {
                block.beginObj(objId, contents.size.toLong(), name)
            } catch (e: BlockFullError) {
                block = createBlock()
                block.beginObj(objId, contents.size.toLong(), name)
            }
        }

        var pos = 0
        while (pos < contents.size) {
            val len = minOf(objDataPageContentLen, contents.size - pos)
            val chunk = contents.copyOfRange(pos, pos + len)

            var wrote = false
            while (!wrote) {
                var cur = blocks.last()
                try {
                    try {
                        cur.updateObj(chunk)
                        wrote = true
                    } catch (indexPageFull: SpiffsFullError) {
                        // The block itself still has room - the current index page's ref list is what's full.
                        if (cur.isFull()) throw BlockFullError()
                        cur.beginObj(objId, contents.size.toLong(), name, indexSpanIx = cur.curObjIndexSpanIx, dataSpanIx = cur.curObjDataSpanIx)
                        // retry this same chunk against the new continuation index page
                    }
                } catch (blockFull: BlockFullError) {
                    val prev = cur
                    val fresh = createBlock()
                    // The file's data pages keep going in the new block, but the *index page* that
                    // tracks them can still be the one physically sitting in the previous block -
                    // an index page's refs are not required to stay within one block.
                    fresh.curObjId = prev.curObjId
                    fresh.curObjIdxPage = prev.curObjIdxPage
                    fresh.curObjDataSpanIx = prev.curObjDataSpanIx
                    fresh.curObjIndexSpanIx = prev.curObjIndexSpanIx
                    // retry this same chunk against the new block
                }
            }
            pos += len
        }
        blocks.last().endObj()
        nextObjId++
    }

    private fun calcMagic(bix: Int, blocksLim: Int): Int {
        var magic = MAGIC_BASE xor pageSize
        magic = magic xor (blocksLim - bix)
        val mask = (2 shl (8 * OBJ_ID_LEN)) - 1
        return magic and mask
    }
}

private fun padWithFF(out: java.io.ByteArrayOutputStream, targetSize: Int) {
    val pad = targetSize - out.size()
    if (pad > 0) repeat(pad) { out.write(0xFF) }
}

/** One file to embed - [path] relative to the SPIFFS root, [content] its raw bytes. */
data class SpiffsSourceFile(val path: String, val content: ByteArray)

// Internal control-flow signals used while packing files into blocks - not real error conditions,
// see SpiffsImageGenerator.createFile()/Block.updateObj() for how each is handled.
private class SpiffsFullError : Exception()
private class BlockFullError : Exception()
