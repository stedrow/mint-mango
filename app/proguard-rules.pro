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

-keepattributes SourceFile,LineNumberTable

# ExoPlayer: keep fully unshrunk/unobfuscated. It's the biggest single dependency and does
# its own internal reflection (optional decoder/extension discovery); shrinking it saves little
# next to the app's own ~11k-line MainActivity and isn't worth the risk on unverifiable hardware.
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# All Bluetooth/AudioSystem/FM-radio reflection in this app targets platform classes
# (android.bluetooth.*, android.media.AudioSystem, com.mediatek.FMRadio.FMRadioNative) that
# live outside this APK's own dex and are never touched by R8 -- no keep rules needed for them.
# The app has no Parcelable/Serializable models and no custom views inflated from XML by class
# name (all views in app/src/main/java/com/themoon/y1/views are constructed with `new`, not
# reflection), so nothing else here depends on exact class/method/field names surviving.