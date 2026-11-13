# Rules for the shrunk build that is installed on the phone (see the debug build type).
# Room, Firebase, OkHttp, Compose and coroutines ship their own rules; these cover the rest.

# Shrink only: remove unused code but keep every name as written. Nothing in Aura needs its names
# hidden (the source is in this repository), and unchanged names keep crash reports readable and
# rule out the commonest shrinker breakage (code that looks things up by name).
-dontobfuscate
# No rewriting of the code that is kept either (no inlining or merging of classes): the shrunk app
# then runs the same code the unit tests ran, just with the unused parts removed.
-dontoptimize
-keepattributes SourceFile,LineNumberTable

# PDF import (pdfbox-android) finds fonts, filters and encodings in ways the shrinker can't follow,
# so it is kept whole. Its optional extras (image codecs, extra crypto) are not included in Aura.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn javax.xml.bind.**
