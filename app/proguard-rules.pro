# R8 / ProGuard rules for Nivara.
#
# The default Android rules plus the rules bundled with R8 already cover the AndroidX,
# Kotlin and Jetpack Compose libraries. Nivara deliberately avoids reflection-based
# libraries at this stage, so no additional keep rules are required yet.
#
# When a later stage introduces reflection-based code (for example a serialization format
# or a platform component that is instantiated by name), add the *narrowest* keep rule that
# makes it work and document why it is needed. Do not disable shrinking or obfuscation.

# Keep annotations that are useful when reading release stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
