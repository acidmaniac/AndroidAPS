plugins {
    id("com.android.application")
}

android {
    compileSdk = Versions.compileSdk
    defaultConfig {
        multiDexEnabled = true
        versionCode = Versions.versionCode
        version = Versions.appVersion

        // Removed after Dagger injection setup in instrumentation tests
        //testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))
        }
        named("debug") {
            // Kover (kover-module-dependencies) owns unit-test coverage via its own JaCoCo agent.
            // AGP's agent here would be a SECOND JaCoCo agent on the same unit-test JVM, defining
            // java.lang.$JaCoCo twice and crashing the test executor. Keep it off.
            enableUnitTestCoverage = false
            // On-device (connected) instrumentation runs in a separate process and feeds the
            // standalone JaCoCo connected-coverage report (Kover cannot collect instrumented tests).
            enableAndroidTestCoverage = true
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = Versions.javaVersion
        targetCompatibility = Versions.javaVersion
    }

    lint {
        checkReleaseBuilds = false
        disable += "MissingTranslation"
        disable += "ExtraTranslation"
    }
}