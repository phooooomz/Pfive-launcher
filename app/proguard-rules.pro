# JNI entry points are looked up by name from native code.
-keep class com.lxlauncher.core.NativeCore { native <methods>; }
-keepclasseswithmembernames class * { native <methods>; }
# The archive engine reports progress by calling onBytes from native code.
-keep interface com.lxlauncher.files.NativeArchive$Progress { *; }
-keep class * implements com.lxlauncher.files.NativeArchive$Progress { boolean onBytes(long); }
