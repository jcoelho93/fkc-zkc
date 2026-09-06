# R8 rules for the release build.
#
# Everything below exists because R8 removes and renames things silently, and this project has
# already lost two rounds to failures whose only symptom was "the feature just doesn't happen"
# (a wrong <meta-data> name, an overlay built on the wrong context). A minified build that boots,
# installs and looks healthy while doing nothing is the exact failure shape to defend against, so
# each rule below names the specific thing that would break without it - and the instrumented
# suite is run against the release variant in CI (see .github/workflows/instrumented-tests.yml)
# so these are checked empirically rather than trusted.
#
# Not needed here, deliberately: keep rules for ScrollMonitorService, MindfulScrollApp,
# MainActivity and BootRescheduleReceiver. AGP generates keep rules from the merged manifest, so
# manifest-declared components are already safe. accessibility_service_config.xml and the strings
# it references are safe for a different reason - resource shrinking is off (isShrinkResources is
# never set), so nothing prunes resources. If resource shrinking is ever turned on, that XML and
# @string/accessibility_service_description need a keep.xml entry, because they are reached only
# through a manifest <meta-data> reference.

# Room, Hilt, and WorkManager ship their own consumer ProGuard rules, so no manual keep rules are
# needed for the framework classes themselves.

# Keep Room entity/DAO classes' generated members reachable for reflection-free codegen.
-keepclassmembers class com.mindfulscroll.app.data.** {
    <init>(...);
}

# Enum CONSTANT NAMES in this package are persisted to disk: Converters stores them with .name and
# reads them back with valueOf(), so a rename turns every existing row into an
# IllegalArgumentException on the user's device after an update - with the old database still
# holding the old spelling. The default android-optimize rules keep values()/valueOf() but not the
# constants themselves, and R8 full mode (the AGP 8 default) is also free to unbox an enum it
# thinks is only used as a tag. This pins both.
-keepclassmembers enum com.mindfulscroll.app.data.entity.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# WorkManager persists the worker's fully-qualified class NAME in its own database and instantiates
# it later by that string, so a rename breaks scheduled work that was enqueued by a previous
# install - and it breaks it in the background, where nothing surfaces the failure. androidx.work's
# own consumer rules cover ListenableWorker subclasses, but this app's workers are @HiltWorker with
# an @AssistedInject constructor rather than the signature those rules describe, so pin them here.
-keep class com.mindfulscroll.app.stats.**Worker { *; }

# The read surface instrumented tests reach into via EntryPointAccessors. Kept so the suite can run
# against the minified variant at all: the test APK is compiled against unminified class names, so
# without this the release run fails with NoClassDefFoundError on the harness instead of testing
# anything. Scoped as tightly as possible on purpose - the accessibility service, its Hilt graph,
# the Room layer and the whole Compose overlay render path stay fully minified, which is what the
# release run is actually there to exercise.
-keep interface com.mindfulscroll.app.accessibility.DiagnosticsEntryPoint { *; }
-keep class com.mindfulscroll.app.accessibility.ServiceDiagnostics { *; }
-keep class com.mindfulscroll.app.accessibility.ServiceDiagnosticsState { *; }
-keep class com.mindfulscroll.app.overlay.OverlayController { *; }
-keep class com.mindfulscroll.app.overlay.OverlayUiState { *; }
