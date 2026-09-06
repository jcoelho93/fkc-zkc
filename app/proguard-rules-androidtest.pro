# R8 rules for the ANDROID TEST apk only (buildTypes.release.testProguardFiles), not for the
# shipped app. Kept separate on purpose: these are all `-dontwarn` entries, and silencing a
# missing-class warning is exactly the kind of thing that must not leak into the app's own rules,
# where an unresolved reference is a signal worth hearing.
#
# Why the test apk gets minified at all: when the tested variant is minified, AGP runs R8 over the
# androidTest apk too, applying the app's mapping so the test's references to app classes line up
# with their new names. That is what makes `connectedReleaseAndroidTest` possible in the first
# place - and it also means the test apk's own dependencies have to satisfy R8.

# error_prone_annotations (arriving transitively through the test dependencies) declares
# @IncompatibleModifiers/@RequiredModifiers with a javax.lang.model.element.Modifier[] member.
# javax.lang.model is a JDK *compiler* API that does not exist on Android and is never loaded at
# runtime - the annotations are compile-time only. R8 has no way to know that, so it has to be
# told, or it fails the build over a reference nothing will ever follow.
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**

# Same category: compile-time-only annotation jars (jsr305 and friends) that no Android runtime
# ships. Listed pre-emptively because they travel with the same dependencies and would otherwise
# each cost a full emulator CI round to discover one at a time.
-dontwarn javax.annotation.**

# androidx.tracing.Trace is called from AndroidJUnitRunner.onCreate(), i.e. before a single test
# runs, and R8 dropped it - the app process died on startup with
# NoClassDefFoundError: Landroidx/tracing/Trace;. It goes missing because the class sits on the
# seam between the two R8 passes: each one can conclude the other apk will provide it, and then
# neither does. Kept on both sides (see proguard-rules-instrumentation.pro) rather than guessing
# which pass dropped it.
-keep class androidx.tracing.** { *; }

# The test apk's size and performance are irrelevant - it is never shipped - so the test
# infrastructure is kept wholesale rather than discovered one NoClassDefFoundError at a time.
# Each such discovery costs a full emulator CI round, and none of them would teach us anything
# about the app, which is the only thing this run exists to exercise.
-keep class androidx.test.** { *; }
-dontwarn androidx.test.**
