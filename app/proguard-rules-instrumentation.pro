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
