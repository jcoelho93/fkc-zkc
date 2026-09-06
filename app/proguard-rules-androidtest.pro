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
