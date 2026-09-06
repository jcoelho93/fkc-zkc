# Applied to the APP's release build ONLY while it is being instrumented
# (-Pmindfulscroll.testBuildType=release), never to the artifact the publish workflow ships.
#
# The distinction matters: the entire point of instrumenting the release variant is that the
# thing under test should be the thing that ships. Anything in this file widens the gap between
# those two, so it stays as close to empty as possible and every entry has to earn its place.

# androidx.tracing.Trace is called by AndroidJUnitRunner.onCreate() before any test runs. The app
# itself never touches it, so R8 is right to strip it from a shipped build - and does. But during
# instrumentation the test apk resolves it against the app apk, finds nothing, and the process
# dies on startup with NoClassDefFoundError before a single test executes.
#
# Keeping one tiny class that the app never calls does not affect what this run is here to
# verify: the accessibility service, its config resource, the Hilt graph, Room, and the whole
# Compose overlay render path are all still fully minified exactly as they ship.
-keep class androidx.tracing.** { *; }

# The Kotlin standard library, kept whole. This is the same seam as androidx.tracing above, but
# it cannot be closed one class at a time: the failure was
# NoClassDefFoundError: Lkotlin/LazyKt; from androidx.test.platform.io.TestDirCalculator, and the
# test apk's own sources are Kotlin too, so between them they reach CollectionsKt, StringsKt,
# ResultKt, Intrinsics and most of the rest. The stdlib lives in the app apk (the test apk does
# not carry a second copy), so whatever the app's R8 pass drops, the test apk cannot resolve.
#
# Known limitation, stated plainly: this means the shipped apk shrinks the stdlib and the
# instrumented one does not, so the two are not byte-identical. It does not weaken what this run
# is here to establish - the resolved accessibility event mask depends on the manifest and the
# config resource, and the overlay path depends on our own code, Hilt, and Compose, all of which
# stay fully minified. It would hide an R8-stripped stdlib function that the app itself needed,
# which is a real gap; catching that is the job of the release build actually running, not of the
# instrumentation.
-keep class kotlin.** { *; }

# Same reasoning, far smaller: the tests read diagnostics state through StateFlow. The app leans
# on coroutines everywhere so most of this survives minification anyway, but the ones the tests
# touch and the app does not would fail exactly like LazyKt did.
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
