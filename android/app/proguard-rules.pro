# R8 rules for the release build (minify + resource shrinking).
#
# Nothing here is needed for correctness today: every library ships its own consumer rules
# (OkHttp, kotlinx-coroutines, WorkManager - which keeps ListenableWorker subclasses such as
# SyncWorker by name - lifecycle-process / androidx.startup, Compose), the manifest keeps the
# Application and Activity classes, and org.json is part of the Android platform.
# The app uses no reflection-based serialization.

# Keep file and line information so release crash stack traces stay readable,
# without exposing the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
