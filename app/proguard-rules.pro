# Boosty ATV release rules.
# Media3 and AndroidX ship their own consumer rules; these keep the parts that
# are referenced from the manifest or resolved indirectly.

-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-renamesourcefileattribute SourceFile

# Only the capture activity is instantiated by name from the manifest and only the
# scanner view is inflated from the library's layout. The rest of ZXing is reached
# through the classes this app calls directly, so keeping whole packages would
# block shrinking of the symbologies and result parsers that are never used.
-keep class com.journeyapps.barcodescanner.CaptureActivity { *; }
-keep class com.journeyapps.barcodescanner.DecoratedBarcodeView { *; }

# Keep JSON models used through reflection-free Android APIs explicit.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Optional dependencies of Media3 / Conscrypt are not bundled by this app.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
