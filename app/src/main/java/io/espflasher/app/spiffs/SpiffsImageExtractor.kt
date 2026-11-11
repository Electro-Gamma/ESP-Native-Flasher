package io.espflasher.app.spiffs

import io.espflasher.app.util.BinaryUtils

/**
 * The inverse of [SpiffsImageGenerator]: given raw SPIFFS image bytes (read
 * straight off a device's SPIFFS partition, or from a local `.bin`), walks
 * every page, reconstructs each file's index+data page chain, and returns
 * its name and content.
 *
 * This shares the exact geometry and on-flash layout [SpiffsImageGenerator]
 * writes - same page-header alignment padding, same 2-byte page references,
 * same object-index-header layout - so it inherits that class's validation
 * history: the format understanding here is the one confirmed against a
 * real `spiffsgen.py` and proven on real hardware, not a fresh guess.
 *
 * Unlike generation, extraction doesn't need the per-block lookup table or
 * magic footer at all - every data/index page is fully self-describing via
 * its own 5-byte header (obj_id, span_ix, flags), so this simply skips the
 * lookup-page region of each block by position and reads every other page
 * directly. It's also deliberately tolerant of a filesystem that's been
 * live on a device (files added/removed via its own web UI, say) rather
 * than freshly generated: pages that were never written, or that carry a
 * deleted/free object id, are just skipped rather than treated as errors.
 */
class SpiffsImageExtractor(
    private val pageSize: Int = 256,
    private val blockSize: Int = 4096
) {
    companion object {
        private const val OBJ_ID_LEN = 2
        private const val OBJ_NAME_LEN = 32
        private const val META_LEN = 4
        private const val IX_FLAG = 0x8000
        private const val FLAGS_UNWRITTEN = 0xFF
        private const val OBJ_ID_FREE = 0xFFFF
        private const val OBJ_ID_DELETED = 0x0000
    }

    private val pagesPerBlock = blockSize / pageSize
    private val objLuPagesPerBlock = ((pagesPerBlock * OBJ_ID_LEN) + pageSize - 1) / pageSize
    private val objDataPageHeaderLen = OBJ_ID_LEN + 2 + 1 // obj_id + span_ix + flags = 5
    private val objDataPageHeaderAlignedPad = (4 - (objDataPageHeaderLen % 4)).let { if (it == 4) 0 else it }
    private val objDataPageHeaderAligned = objDataPageHeaderLen + objDataPageHeaderAlignedPad // 8
    private val objIndexPagesHeaderLen = objDataPageHeaderAligned + 4 + 1 + OBJ_NAME_LEN + META_LEN // 49

    init {
        require(blockSize % pageSize == 0) { "blockSize ($blockSize) must be a multiple of pageSize ($pageSize)" }
        require(objLuPagesPerBlock < pagesPerBlock) { "blockSize/pageSize combination leaves no room for data" }
    }

    private data class IndexPageInfo(val spanIx: Int, val size: Long?, val name: String?, val refs: List<Int>)

    /**
     * @param image raw SPIFFS partition bytes - size must be a whole number
     *   of [blockSize] (a straight read of the partition already is).
     */
    fun extract(image: ByteArray): List<SpiffsExtractedFile> {
        require(image.size % blockSize == 0) {
            "Image size (${image.size}) isn't a whole number of blocks (blockSize=$blockSize) - did the read get cut short?"
        }
        val totalBlocks = image.size / blockSize

        val indexPagesByObjId = mutableMapOf<Int, MutableList<IndexPageInfo>>()
        val dataPageContent = mutableMapOf<Int, ByteArray>() // global page number -> raw content bytes

        for (bix in 0 until totalBlocks) {
            val blockOffset = bix * blockSize
            for (localPage in objLuPagesPerBlock until pagesPerBlock) {
                val globalPage = bix * pagesPerBlock + localPage
                val pageOffset = blockOffset + localPage * pageSize
                val flags = image[pageOffset + 4].toInt() and 0xFF
                if (flags == FLAGS_UNWRITTEN) continue // never written - nothing here

                val rawObjId = BinaryUtils.readU16LE(image, pageOffset).toInt()
                val spanIx = BinaryUtils.readU16LE(image, pageOffset + 2).toInt()
                val isIndex = (rawObjId and IX_FLAG) != 0
                val objId = rawObjId and IX_FLAG.inv()
                if (objId == OBJ_ID_FREE || objId == OBJ_ID_DELETED) continue // stale/GC'd slot, not live content

                if (isIndex) {
                    var p = pageOffset + objDataPageHeaderAligned
                    var size: Long? = null
                    var name: String? = null
                    if (spanIx == 0) {
                        size = BinaryUtils.readU32LE(image, p); p += 4
                        p += 1 // type byte, not needed for extraction
                        val nameBytes = image.copyOfRange(p, p + OBJ_NAME_LEN)
                        name = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
                        p += OBJ_NAME_LEN + META_LEN
                    }
                    val refBytesAvailable = pageSize - (p - pageOffset)
                    val refs = mutableListOf<Int>()
                    var i = 0
                    while (i < refBytesAvailable / 2) {
                        val refPageNum = BinaryUtils.readU16LE(image, p + i * 2).toInt()
                        if (refPageNum == OBJ_ID_FREE) break // unused tail slot - generator pads with 0xFF, matches "no more refs"
                        refs += refPageNum
                        i++
                    }
                    indexPagesByObjId.getOrPut(objId) { mutableListOf() } += IndexPageInfo(spanIx, size, name, refs)
                } else {
                    dataPageContent[globalPage] = image.copyOfRange(pageOffset + objDataPageHeaderLen, pageOffset + pageSize)
                }
            }
        }

        val results = mutableListOf<SpiffsExtractedFile>()
        for ((_, pages) in indexPagesByObjId) {
            val sorted = pages.sortedBy { it.spanIx }
            val header = sorted.firstOrNull { it.spanIx == 0 } ?: continue
            val name = header.name?.takeIf { it.isNotEmpty() } ?: continue
            val totalSize = header.size ?: continue

            val out = java.io.ByteArrayOutputStream(totalSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0))
            for (info in sorted) {
                for (pageNum in info.refs) {
                    dataPageContent[pageNum]?.let { out.write(it) }
                }
            }
            val fullBytes = out.toByteArray()
            val content = if (fullBytes.size.toLong() > totalSize) fullBytes.copyOf(totalSize.toInt()) else fullBytes
            results += SpiffsExtractedFile(name, content)
        }
        return results
    }
}

/** One extracted file - [path] exactly as stored in the SPIFFS image (leading "/", no real directory tree), [content] its bytes. */
data class SpiffsExtractedFile(val path: String, val content: ByteArray)
