pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required for com.github.felHR85:UsbSerial, used internally by the
        // vendored :arduino-hex-upload module's serial (non-USBasp) sub-modes.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ESP Native Flasher"
include(":app")
include(":arduino-hex-upload")
include(":IntelHexFormatReader")
