# The bridge to the Rust core: JNA, and the Kotlin that UniFFI generates. Native code and reflection reach these
# by name, which R8 cannot see. Written by shape, not by package: renaming the app cannot silently drop a rule.

# JNA draws on desktop windows too: those classes do not exist on Android and are never reached.
-dontwarn java.awt.**

# The native half of JNA looks up its own classes, fields and methods by name.
-keep class com.sun.jna.** { *; }

# Structures and callbacks: JNA reads their fields and finds their method by reflection.
-keep class * implements com.sun.jna.** { *; }

# The generated Kotlin binds each `external fun` to the symbol of the core that carries its name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
