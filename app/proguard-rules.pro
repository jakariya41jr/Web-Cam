# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Preserve Xposed entry point instantiated reflectively by LSPosed from assets/xposed_init
-keep class com.example.xposed.VirtualCamHookEntry {
    public <init>();
    public void handleLoadPackage(...);
}

# Preserve self-hook status helper methods
-keepclassmembers class com.example.xposed.ModuleStatus {
    public static boolean isModuleActive();
    public static int getXposedApiVersion();
}
