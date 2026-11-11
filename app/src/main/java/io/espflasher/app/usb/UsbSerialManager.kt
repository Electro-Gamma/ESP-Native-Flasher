package io.espflasher.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import io.espflasher.app.protocol.FlasherException
import io.espflasher.app.usb.drivers.CdcAcmSerialDriver
import io.espflasher.app.usb.drivers.Ch340SerialDriver
import io.espflasher.app.usb.drivers.Cp210xSerialDriver
import io.espflasher.app.usb.drivers.FtdiSerialDriver
import io.espflasher.app.usb.drivers.Pl2303SerialDriver
import kotlinx.coroutines.CompletableDeferred

/**
 * Owns the Android [UsbManager] handle: enumerates candidate devices,
 * requests runtime permission, and builds the right [UsbSerialPort]
 * implementation for whichever bridge chip is plugged in.
 */
class UsbSerialManager(private val context: Context) {

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "io.espflasher.app.USB_PERMISSION"

        private const val VID_SILABS = 0x10C4
        private const val VID_WCH = 0x1A86
        private const val VID_FTDI = 0x0403
        private const val VID_PROLIFIC = 0x067B
        private const val VID_ESPRESSIF = 0x303A
    }

    fun isUsbHostSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /** All attached USB devices, tagged with the chip family we think they are. */
    fun listDevices(): List<UsbDeviceInfo> =
        usbManager.deviceList.values.map { toDeviceInfo(it) }

    fun findUsbDevice(androidDeviceName: String): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceName == androidDeviceName }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    private fun identifyChipFamily(device: UsbDevice): ChipFamily = when (device.vendorId) {
        VID_SILABS -> ChipFamily.CP210X
        VID_WCH -> ChipFamily.CH340
        VID_FTDI -> ChipFamily.FTDI
        VID_PROLIFIC -> ChipFamily.PL2303
        VID_ESPRESSIF -> ChipFamily.CDC_ACM
        else -> if (hasCdcInterface(device)) ChipFamily.CDC_ACM else ChipFamily.UNKNOWN
    }

    private fun hasCdcInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
            ) return true
        }
        return false
    }

    fun toDeviceInfo(device: UsbDevice): UsbDeviceInfo = UsbDeviceInfo(
        vendorId = device.vendorId,
        productId = device.productId,
        manufacturerName = device.manufacturerName,
        productName = device.productName,
        serialNumber = if (hasPermission(device)) runCatching { device.serialNumber }.getOrNull() else null,
        androidDeviceName = device.deviceName,
        chipFamily = identifyChipFamily(device)
    )

    /**
     * Suspends until the user answers the system USB-permission prompt (or
     * grants it automatically if already held). Returns true if granted.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                context.unregisterReceiver(this)
                if (!result.isCompleted) result.complete(granted)
            }
        }
        // UsbManager.requestPermission() needs a mutable PendingIntent - the
        // system attaches EXTRA_PERMISSION_GRANTED to it when firing the
        // broadcast back, which an immutable one can't accept. Android 14+
        // (API 34) separately refuses FLAG_MUTABLE on an intent it considers
        // "implicit" - and that check looks specifically for an explicit
        // *component* (a target class via setClass/ComponentName), which
        // setPackage() alone does NOT provide. A dynamically-registered
        // BroadcastReceiver like the one below has no fixed class to target
        // in the first place, so there's no way to make this intent
        // explicit in the sense Android wants here. FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
        // (API 34+) is the framework's own sanctioned opt-out for exactly
        // this situation - still scoped to this app via setPackage below as
        // defense in depth, just not relying on that alone to satisfy the check.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) or
            (if (android.os.Build.VERSION.SDK_INT >= 34) PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT else 0)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbManager.requestPermission(device, permissionIntent)

        return result.await()
    }

    /** Claims interfaces/endpoints and returns an opened-but-not-yet-[UsbSerialPort.open] driver instance. */
    fun buildPort(device: UsbDevice, config: SerialConfig): UsbSerialPort {
        val family = identifyChipFamily(device)
        val info = toDeviceInfo(device)
        val connection = usbManager.openDevice(device)
            ?: throw FlasherException.Generic("Could not open USB connection to ${info.displayLabel}.")

        return when (family) {
            ChipFamily.CP210X -> {
                val iface = device.getInterface(0)
                val (inEp, outEp) = findBulkEndpoints(iface)
                    ?: throw FlasherException.Generic("No bulk endpoints found on ${info.displayLabel}.")
                Cp210xSerialDriver(connection, iface, inEp, outEp, info, config)
            }
            ChipFamily.CH340 -> {
                val iface = device.getInterface(0)
                val (inEp, outEp) = findBulkEndpoints(iface)
                    ?: throw FlasherException.Generic("No bulk endpoints found on ${info.displayLabel}.")
                Ch340SerialDriver(connection, iface, inEp, outEp, info, config)
            }
            ChipFamily.FTDI -> {
                val iface = device.getInterface(0)
                val (inEp, outEp) = findBulkEndpoints(iface)
                    ?: throw FlasherException.Generic("No bulk endpoints found on ${info.displayLabel}.")
                FtdiSerialDriver(connection, iface, inEp, outEp, info, config)
            }
            ChipFamily.PL2303 -> {
                val iface = device.getInterface(0)
                val (inEp, outEp) = findBulkEndpoints(iface)
                    ?: throw FlasherException.Generic("No bulk endpoints found on ${info.displayLabel}.")
                Pl2303SerialDriver(connection, iface, inEp, outEp, info, config)
            }
            ChipFamily.CDC_ACM -> buildCdcAcmPort(device, connection, info, config)
            ChipFamily.UNKNOWN -> throw FlasherException.Generic(
                "Unrecognized USB-UART bridge (VID:PID ${info.vidPidHex}). Supported chips: CP210x, CH340/341, FTDI FT232, PL2303, CDC-ACM."
            )
        }
    }

    private fun buildCdcAcmPort(
        device: UsbDevice,
        connection: android.hardware.usb.UsbDeviceConnection,
        info: UsbDeviceInfo,
        config: SerialConfig
    ): UsbSerialPort {
        var controlInterface: UsbInterface? = null
        var dataInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            when (iface.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> controlInterface = iface
                UsbConstants.USB_CLASS_CDC_DATA -> dataInterface = iface
            }
        }
        // Some CDC composite devices report everything under a single vendor-specific
        // interface (no separate COMM/DATA split); fall back to interface 0 for both.
        val effectiveData = dataInterface ?: device.getInterface(0)
        val effectiveControl = controlInterface ?: effectiveData
        val (inEp, outEp) = findBulkEndpoints(effectiveData)
            ?: throw FlasherException.Generic("No bulk endpoints found on ${info.displayLabel}.")
        return CdcAcmSerialDriver(connection, effectiveData, effectiveControl, inEp, outEp, info, config)
    }

    private fun findBulkEndpoints(iface: UsbInterface): Pair<android.hardware.usb.UsbEndpoint, android.hardware.usb.UsbEndpoint>? {
        var inEp: android.hardware.usb.UsbEndpoint? = null
        var outEp: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        return if (inEp != null && outEp != null) inEp to outEp else null
    }
}
