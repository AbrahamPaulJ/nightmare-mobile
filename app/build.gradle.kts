import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

/**
 * ⭐⭐ Release signing, read from OUTSIDE the repository.
 *
 * ⚠⚠ `../.secrets/nightmare-keystore/` is a sibling of the repo, not a
 * gitignored path inside it. Gitignore is one `git add -f` away from
 * committing a private key; a file that is not in the tree at all cannot be
 * committed by accident.
 *
 * ⚠⚠ A MISSING keystore is not an error. The release build still runs and
 * produces an unsigned APK, so a fresh clone on another machine can build
 * and test the release variant without holding the key. Failing here would
 * make the project unbuildable for everyone except one laptop, which is a
 * strange thing for a public repository to do.
 *
 * ⚠ Android identifies an app by its signature: lose this key and no future
 * build can update an installed copy. `facefusion-mobile` lost one already.
 * The README beside the keystore says what to do about it.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("../.secrets/nightmare-keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}


android {
    namespace = "com.abrah.nightmare"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abrah.nightmare"
        // 31 to match DreamUI. The backend and QNN runtime set the real floor;
        // there is no reason to support anything the NPU path cannot run on.
        minSdk = 31
        targetSdk = 35
        // ⚠ Bump on EVERY push. The harness prints versionName, and two builds
        // sharing a version makes a failure report unattributable -- DreamUI lost
        // five releases to this exact mistake.
        //
        // ⚠⚠ THREE-part versionName, and an ordinary push increments the
        // PATCH: 1.2.0 -> 1.2.1 -> 1.2.2. ⚠⚠ Getting the FORMAT right is not
        // getting the GRANULARITY right -- this went 1.0.12 -> 1.1.0 -> 1.2.0,
        // a minor bump per push, which is what the rule exists to stop. The
        // minor moves only when a release is called a release. ⚠ versionCode
        // stays a plain incrementing integer; Android requires that.
        versionCode = 115
        versionName = "1.4.0"
        ndk { abiFilters += "arm64-v8a" }

        // The plugin runtime, built from source. ⚠ arm64 only, like everything
        // else here: the NPU path has no other target, and building quickjs.c
        // (2.1 MB of C) four times for ABIs that can never run a model is pure
        // build time.
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=none" }
        }
    }

    // ⚠ Our own C, unlike the backend: libstable_diffusion_core.so is BUILT
    // ELSEWHERE and copied into jniLibs by tools/stage_backend.ps1 (it is an
    // executable, and its tree is CC BY-NC and uncommittable). libnmjs.so is
    // ours plus MIT QuickJS, so it is a normal Gradle native build -- and that
    // means it cannot silently go stale the way a staged binary can.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(
                    "../.secrets/nightmare-keystore/" + keystoreProps.getProperty("storeFile")
                )
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // ⚠ AGP leaves v3 OFF by default, and v3 is the scheme that
                // makes KEY ROTATION possible later. It cannot be added
                // retroactively to an APK people have already installed, so it
                // goes on before the first release rather than after.
                //
                // ⚠⚠ MEASURED 2026-09-11: with v3 on, AGP emits a v3 block
                // INSTEAD of v2, not alongside it -- `apksigner verify` reports
                // v2 false, v3 true however `enableV2Signing` is set. That is
                // fine here and only here: v3 needs API 28 and minSdk is 31, so
                // every device that can install this app can verify it. Lower
                // the minSdk and this line becomes a bug.
                //
                // v1 is dead weight at minSdk 31; v4 needs a separate .idsig
                // file that a sideloaded APK has no use for.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // âš  Not minified, unlike DreamUI's debug build -- and that is only
        // tenable because the dex is small. MEASURED 2026-09-07: with
        // material-icons-extended on the classpath the unminified APK was
        // 55.6 MB, of which 55.0 MB was dex (44.4 + 10.6). Dropping that one
        // unused dependency took it to the size below. DreamUI hit the identical
        // wall at 43.8 MB and solved it by minifying debug instead.
        // â‡’ Revisit the moment the dex grows again; unminified is a convenience,
        // not a principle, and stack traces are what it buys.
        debug {
            isMinifyEnabled = false
        }
        release {
            // ⚠ Null when there is no keystore, which leaves the APK unsigned
            // rather than failing the build.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Roborazzi renders through Robolectric, which needs real resources.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs {
            // extractNativeLibs=true: the forked backend must exist as a real
            // file in nativeLibraryDir so it can be exec'd. Execution from the
            // writable app data dir is blocked; nativeLibraryDir is not
            // writable, so it is the one allowed location. Set now so the
            // packaging is right before the binary arrives.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libstable_diffusion_core.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // âš  Compose BOM 2024.10.01 gives material3 1.3.1, which is NOT Material 3
    // Expressive (needs 1.4+). Pinned here anyway because this exact set is
    // already in the Gradle cache, so the first build proves the scaffold rather
    // than dependency resolution. The Expressive bump is a separate, isolated
    // change -- see docs/UI.md section 1.
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // âš  material-icons-extended is deliberately ABSENT. It is thousands of
    // generated vector classes and costs ~55 MB of dex on its own (measured
    // above) whether or not a single icon is referenced. Add individual icons,
    // or the base `material-icons-core`, if one is actually needed.

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // â­ The inner loop of docs/UI.md: previews are the agent's cheap eyes, so
    // the tooling that renders them is a first-class dependency, not an extra.
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Roborazzi: composables to PNG on the JVM, no device, no IDE.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.26.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
