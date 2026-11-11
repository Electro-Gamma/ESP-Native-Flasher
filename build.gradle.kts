// Top-level build file. Individual module build files declare their own
// plugin versions against these shared plugin ids.
plugins {
    id("com.android.application") version "8.13.0" apply false
    // Needed by the vendored :arduino-hex-upload and :IntelHexFormatReader
    // modules (see ArduinoHexUploadOperationManager's doc comment) -
    // com.android.library for :arduino-hex-upload itself; kotlin.kapt is
    // declared in its build.gradle even though nothing there currently
    // triggers annotation processing, kept for parity with the reference
    // project rather than risk removing something a future change needs.
    id("com.android.library") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
}
