# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   https://developer.android.com/topic/performance/app-optimization/enable-app-optimization

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# GeckoView parses the start-up config file (EngineProvider) with SnakeYAML, whose static initialisers call
# Class.getPackage().getName(): repackaged into the default package they crash. Keep its names, allow shrinking.
-keep,allowshrinking,allowoptimization class org.yaml.snakeyaml.** { *; }

# Release builds keep browsing URLs out of logcat: GeckoView logs every page load's URI at debug level
# (GeckoSession "handleMessage ... uri="), and debug/verbose lines are no use outside a debug build.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
