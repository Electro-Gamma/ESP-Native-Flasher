# ESP Native Flasher - ProGuard / R8 rules
# Minification is disabled by default in both build types (see app/build.gradle.kts).
# These rules only take effect if you flip isMinifyEnabled = true for a release build.

# Keep protocol data classes intact for reliable field access via reflection-based
# tooling (e.g. if you later add JSON export of chip info).
-keep class io.espflasher.app.protocol.** { *; }
-keep class io.espflasher.app.usb.** { *; }
-keepattributes Signature, InnerClasses, *Annotation*
