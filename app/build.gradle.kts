plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release version comes from the git tag the publish workflow builds (tag `v0.2.0` sets
 * VERSION_NAME=0.2.0); local builds fall back to a dev version.
 *
 * versionCode is derived from versionName rather than hand-maintained because it MUST increase on
 * every release. Android, Obtainium and F-Droid all use it to tell a new build from the installed
 * one, and a permanently-constant versionCode is what previously forced an uninstall-and-reinstall
 * for every update - which in turn meant re-granting the accessibility permission every time.
 */
val appVersionName: String = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"

val appVersionCode: Int = appVersionName.substringBefore('-')
    .split('.')
    .let { parts ->
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        major * 1_000_000 + minor * 1_000 + patch
    }
    .coerceAtLeast(1)

/**
 * Written by the publish workflow after it decodes the keystore secret; absent for local builds,
 * which then produce an unsigned release APK instead of failing.
 */
val releaseKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

/**
 * Which build type `connectedAndroidTest` installs and instruments. Defaults to debug; CI also
 * runs the whole instrumented suite with `-Pmindfulscroll.testBuildType=release` because R8 is
 * exactly the kind of thing that breaks manifest-referenced and reflectively-reached classes
 * without a single error - twice already the failures on this project have been silent ones, and
 * the resolved accessibility event mask and the overlay window are runtime facts that no static
 * check of the APK can answer.
 */
val testBuildTypeName: String =
    providers.gradleProperty("mindfulscroll.testBuildType").getOrElse("debug")

/**
 * Lets a release build be signed with the debug key so it can be installed on an emulator and
 * instrumented. Deliberately an explicit opt-in property rather than an automatic fallback: the
 * publish workflow relies on a missing keystore producing an UNSIGNED apk (which its
 * "Verify the APK is signed" step then catches), and a silent fall back to the debug key would
 * turn that loud failure into an apk that installs fine and can never be updated in place.
 */
val signReleaseWithDebugKey: Boolean =
    providers.gradleProperty("mindfulscroll.signReleaseWithDebugKey").orNull.toBoolean()

android {
    namespace = "com.mindfulscroll.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mindfulscroll.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testBuildType = testBuildTypeName

    signingConfigs {
        create("release") {
            // Local val so Kotlin can smart-cast it; a script-level property can't be smart-cast.
            val path = releaseKeystorePath
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Only wired up when the workflow supplied a keystore. Without this guard a local
            // `assembleRelease` would fail on a signing config with a null storeFile instead of
            // just producing an unsigned APK.
            signingConfig = when {
                releaseKeystorePath != null -> signingConfigs.getByName("release")
                // Instrumentation only - see signReleaseWithDebugKey above.
                signReleaseWithDebugKey -> signingConfigs.getByName("debug")
                else -> null
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Applies to the androidTest apk only. AGP runs R8 over it as well whenever the
            // tested variant is minified, and its test-only dependencies need rules the shipped
            // app must not inherit - see the file's own header.
            testProguardFiles("proguard-rules-androidtest.pro")

            // Only while this variant is the one being instrumented. Conditional rather than
            // unconditional so the published apk is unchanged by the existence of the test run:
            // instrumenting the release build is worth doing precisely because the thing tested
            // is the thing shipped, and every rule here widens that gap. See the file's header.
            if (testBuildTypeName == "release") {
                proguardFiles("proguard-rules-instrumentation.pro")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("debug") {
            kotlin.srcDirs("src/debug/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            // Everything here must compile and run against BOTH variants, so it may not touch
            // anything from src/debug. Debug-only instrumented tests go in androidTestDebug.
            kotlin.srcDirs("src/androidTest/kotlin")
        }
        getByName("androidTestDebug") {
            // Tests that depend on debug-only components (ScrollProbeActivity) and so cannot run
            // against the release variant.
            kotlin.srcDirs("src/androidTestDebug/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // Used only by the androidTestDebug suite (createAndroidComposeRule also needs
    // ui-test-manifest, which is debug-only), but declared for both: it is inert in the release
    // androidTest APK, and scoping it to androidTestDebugImplementation would mean depending on a
    // configuration AGP has no reason to create when testBuildType is release.
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
