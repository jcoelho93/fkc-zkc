# Applied to the APP's release build ONLY while it is being instrumented
# (-Pmindfulscroll.testBuildType=release), never to the artifact the publish workflow ships.
#
# The distinction matters: the entire point of instrumenting the release variant is that the
# thing under test should be the thing that ships. Anything in this file widens the gap between
# those two, so it stays as close to empty as possible and every entry has to earn its place.
#
# ---------------------------------------------------------------------------------------------
# THE MEASURED GAP (#9). The instrumented release apk is NOT the shipped one.
#
# This file is the only difference between the two builds' R8 runs (see app/build.gradle.kts),
# so every difference below is caused by it. Measured on main @ c083fc0, counting fully-removed
# classes only (usage.txt lines with no trailing ':'):
#
#     20,527  fully removed in the shipped build
#     19,067  fully removed in the instrumented build
#      1,579  removed in shipped, KEPT in instrumented   <- the gap
#        119  removed in instrumented, kept in shipped
#
#   The 1,579, by package:
#        777  kotlin (stdlib)
#        605  kotlinx.coroutines        - the service's threshold timer runs on these
#        135  androidx.compose
#         48  androidx (other: collection, work, room, navigation, lifecycle...)
#         10  the app's own classes
#          4  other (_COROUTINE debug frames)
#
# The last-but-one line is the part the older comments here got wrong. Keeping the stdlib and
# coroutines whole changes R8's OPTIMISATION decisions downstream, not just what survives, so the
# app's own code is not shrunk "exactly as it ships" either. Of the 10: SessionState and
# ThresholdConfig are class-inlined in the shipped build (never allocated; ThresholdEvaluator.
# evaluate is inlined into ScrollMonitorService and their fields become its locals) but kept as
# real objects in the instrumented one, so the threshold path runs in a different shape under
# test than on a phone. The other 8 are
# compiler-generated lambda classes (Compose `items {}` and nav callbacks).
#
# What the release run still establishes, exactly: the resolved accessibility event mask (a fact
# about the manifest and the config resource, which this file cannot touch), and that the
# overlay window draws with the app, Hilt, Room and Compose all minified - minified, but not
# identically optimised. What it cannot catch: R8 stripping or rewriting a stdlib or coroutines
# class the app needs at runtime, or an optimisation that only happens in the shipped shape.
# Nothing automated runs the shipped apk; verify_release_apk.sh inspects it statically only.
#
# Re-measure after any dependency, AGP, Kotlin or keep-rule change (JAVA_HOME = JDK 17):
#
#     .github/scripts/r8_instrumentation_gap.sh
#
# CI prints the same table to the `build` job's summary on every PR.
# ---------------------------------------------------------------------------------------------

# androidx.tracing.Trace is called by AndroidJUnitRunner.onCreate() before any test runs. The app
# itself never touches it, so R8 is right to strip it from a shipped build - and does. But during
# instrumentation the test apk resolves it against the app apk, finds nothing, and the process
# dies on startup with NoClassDefFoundError before a single test executes.
#
# One tiny class the app never calls: on its own it would barely register in the gap above. The
# two wholesale keeps below are what the numbers are made of.
-keep class androidx.tracing.** { *; }

# The Kotlin standard library, kept whole. This is the same seam as androidx.tracing above, but
# it cannot be closed one class at a time: the failure was
# NoClassDefFoundError: Lkotlin/LazyKt; from androidx.test.platform.io.TestDirCalculator, and the
# test apk's own sources are Kotlin too, so between them they reach CollectionsKt, StringsKt,
# ResultKt, Intrinsics and most of the rest. The stdlib lives in the app apk (the test apk does
# not carry a second copy), so whatever the app's R8 pass drops, the test apk cannot resolve.
#
# Known limitation, stated plainly: the shipped apk shrinks the stdlib and the instrumented one
# does not - 777 classes' worth, see THE MEASURED GAP above - and the knock-on effect on R8's
# optimisation reaches the app's own classes too. It would hide an R8-stripped stdlib function
# that the app itself needed; nothing automated catches that, only the shipped build running.
-keep class kotlin.** { *; }

# Same reasoning: the tests read diagnostics state through StateFlow, and the ones the tests
# touch and the app does not would fail exactly like LazyKt did. Not smaller in effect, though:
# 605 coroutines classes are shrunk in the shipped build and kept here, and coroutines are what
# the service's threshold timer runs on, so that path is not exercised in its shipped form.
-keep class kotlinx.coroutines.** { *; }

# Hilt's entry-point accessors, which ONLY the test apk calls: the app itself never reaches into
# its own graph this way, so R8 correctly strips them from a shipped build - and then the harness
# cannot resolve them. The same app/test seam as androidx.tracing and kotlin.LazyKt above.
#
# Named as two specific classes rather than dagger.hilt.**: these are just the static lookup
# helpers, so keeping them leaves the generated component, the modules and every injected class
# fully minified. Hilt surviving minification is one of the things this run exists to check, and
# keeping it wholesale would quietly cancel that.
-keep class dagger.hilt.android.EntryPointAccessors { *; }
-keep class dagger.hilt.EntryPoints { *; }
