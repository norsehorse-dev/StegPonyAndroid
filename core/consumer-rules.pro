# The generated UniFFI bindings are plain JNA: Structure fields are read by
# declared order, the Library interface's method names are the Rust symbol
# names, and callback interfaces are invoked from native code. R8 renaming or
# removing any of it breaks the first call into the core in release builds only.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**

-keep class uniffi.stegpony_ffi.** { *; }
-keep interface uniffi.stegpony_ffi.** { *; }
