# Boosty ATV release rules.
# Media3 and AndroidX ship their own consumer rules; these keep the parts that
# are referenced from the manifest or resolved indirectly.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ZXing creates its activities and views from the manifest/library metadata.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# Keep JSON models used through reflection-free Android APIs explicit.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Optional dependencies of Media3 / Conscrypt are not bundled by this app.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
