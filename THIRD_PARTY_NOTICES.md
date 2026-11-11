# Third-party notices

ESP Native Flasher is licensed under the GNU General Public License v3.0 or later (see [LICENSE](LICENSE)).
This file lists the third-party projects the application includes, links to, or follows as a reference.
Each keeps its own license and copyright.

## Reference implementation

### Espressif esptool

| | |
|---|---|
| Upstream project | https://github.com/espressif/esptool |
| Copyright | Copyright (C) 2014 and later Fredrik Ahlberg, Angus Gratton, Espressif Systems (Shanghai) CO LTD, and other contributors as noted in the upstream source files |
| License | GNU General Public License, version 2 or later (GPL-2.0-or-later). Text: [licenses/GPL-2.0.txt](licenses/GPL-2.0.txt) |
| Included in this repository or APK | **No.** No esptool source file, binary, Python package or stub is included, bundled or executed by the app |

esptool is the reference implementation of the ESP serial bootloader protocol. ESP Native Flasher is a separate, original Kotlin implementation of that protocol. Where the source comments say so, the Kotlin code follows esptool's observable behaviour. The parts concerned are:

| Area | Files in this repository | What follows esptool |
|---|---|---|
| Transfer and timeout behaviour | `protocol/EspRomLoader.kt` | Per-megabyte timeouts, flash-MD5 handling, stub read pipelining |
| Flash erase sizing | `protocol/FlashLayout.kt` | The erase-size calculation used for `FLASH_BEGIN`, including the ESP8266 workaround |
| Chip capability gates and register bases | `protocol/ChipType.kt` | Which chips support compressed writes and MD5, and the SPI register bases of newer chips |
| Compression | `protocol/Compressor.kt` | zlib compression for the `FLASH_DEFL_*` commands |
| Image header tables | `protocol/ImageHeader.kt` | Flash mode and frequency code tables |
| Write, verify and ESP8266 sequencing | `flashing/FlashOperationManager.kt` | Command ordering, the ESP8266 SPI attach and MD5 behaviour |
| Defaults | `usb/SerialConfig.kt`, `flashing/FlashOperationManager.kt` | 115200 8N1 and the 921600 upload-baud suggestion |

`protocol/PartitionTable.kt` is documented in its source as written from Espressif's public format description rather than copied from esptool. The command opcodes and register addresses are hardware and protocol facts published in Espressif's documentation.

esptool's GPL-2.0-or-later license is compatible with this project's GPL-3.0-or-later license.

## Bundled files and vendored code

### Espressif esp-flasher-stub (flasher stubs)

| | |
|---|---|
| Upstream project | https://github.com/espressif/esp-flasher-stub (release v1.0.0) |
| Copyright | Copyright (c) 2025 Espressif Systems (Shanghai) CO LTD |
| License | Apache-2.0 **or** MIT (dual-licensed) |
| Location | `app/src/main/assets/stub_flasher/*.json`, with `LICENSE-APACHE`, `LICENSE-MIT` and `README.md` alongside |

These stubs are published by Espressif separately from esptool.

### avrdude (USBasp driver)

| | |
|---|---|
| Upstream project | https://github.com/avrdudes/avrdude |
| License | GNU General Public License, version 2 or later. Text: [licenses/GPL-2.0.txt](licenses/GPL-2.0.txt) |
| Location | `arduino-hex-upload/src/main/java/kr/co/makeitall/arduino/usbasp/` |
| Use | The USBasp control-transfer driver is ported from avrdude 8.2's `usbasp.c` according to its source comments. Only that driver is derived; avrdude itself is not included |

This derived code is the reason the project is released under GPL-3.0-or-later.

### ESP-IDF `spiffsgen.py` (SPIFFS image generator)

| | |
|---|---|
| Upstream project | https://github.com/espressif/esp-idf (`components/spiffs/spiffsgen.py`) |
| Copyright | Espressif Systems (Shanghai) |
| License | Apache-2.0 |
| Location | `app/src/main/java/io/espflasher/app/spiffs/SpiffsImageGenerator.kt` |
| Use | A Kotlin port of the generator's structure. Changed from the original: translated to Kotlin and adapted to run on Android |

### ArduinoSketchUploader and the Android port `kr.co.makeitall.arduino`

| | |
|---|---|
| Upstream | ArduinoSketchUploader (C#) and the Android port vendored as `kr.co.makeitall.arduino` |
| License | MIT upstream |
| Location | `arduino-hex-upload/` (STK500v1, STK500v2, AVR109 bootloader programmers and board definitions) |

### IntelHexFormatReader

| | |
|---|---|
| Upstream | IntelHexFormatReader (Intel HEX parser), Java port |
| Location | `IntelHexFormatReader/` |

### Small utility classes

| Class | Author | License | Location |
|---|---|---|---|
| `LineReader` | Torleif Berger (http://www.geekality.net/?p=1614) | Creative Commons Attribution 3.0 (as stated in the source header) | `IntelHexFormatReader/.../LineReader.java`, `arduino-hex-upload/.../LineReader.kt` |
| `StringHelper` | Tangible Software Solutions, Inc. | Free to use provided the copyright notice remains (as stated in the source header) | `IntelHexFormatReader/.../csharpstyle/StringHelper.java` |
| `FileLineIterable` | Carey Brown | As stated in the source header | `IntelHexFormatReader/.../FileLineIterable.java` |

### Protocol ports and bundled binary for other modules

| Component | Location | Notes |
|---|---|---|
| Realtek AmebaD flashloader binary and protocol (BW16) | `app/src/main/assets/ameba/flashloader.bin`, `app/src/main/java/io/espflasher/app/ameba/` | The protocol code is a port of the `upload_amebad.py` flashing tool used with Realtek AmebaD |
| SIM800 boot protocol, ROM-string editor and IMEI utilities | `app/src/main/java/io/espflasher/app/sim800/` | Ports of the maintainer's `sim800flasher2.py`, `edit_firmware_name.py` and `sim800_change_imei.py` scripts. No SIMCom firmware is included |
| Firmware credential patcher | `app/src/main/java/io/espflasher/app/flashing/BinaryCredentialPatcher.kt` | Port of the maintainer's `ESP_all_final_x4.py` `update_binary()` function |

## Libraries resolved by Gradle

| Library | License |
|---|---|
| AndroidX (core-ktx, appcompat, activity, fragment, lifecycle, recyclerview, constraintlayout, coordinatorlayout, documentfile, swiperefreshlayout, DataStore) | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines | Apache-2.0 |
| Timber (Arduino module) | Apache-2.0 |
| felHR85 UsbSerial 6.1.0 (via JitPack, Arduino module) | MIT |

## Source availability

The complete corresponding source for every released APK is this repository at the matching release tag.
