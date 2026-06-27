import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath(libs.com.android.tools.build)
        classpath(libs.com.google.gms)
        classpath(libs.com.google.firebase.gradle)

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files

        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.kotlin.allopen)
        classpath(libs.kotlin.serialization)
    }
}

plugins {
    alias(libs.plugins.klint)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler) apply false
    // Applied to the root: it is the coverage-merging project. Apply by id WITHOUT a version
    // because the Kover plugin is already on the classpath via buildSrc (kover-gradle-plugin),
    // and the kover-module-dependencies convention plugin applies it to each module.
    id("org.jetbrains.kotlinx.kover")
    id(libs.plugins.android.test.get().pluginId) apply false
}

// Dagger/Hilt (≥2.57 unshades kotlin-metadata-jvm) ships a metadata reader that lags new Kotlin releases.
// Pin it to the Kotlin version so the Hilt KSP processor can parse current class metadata; captured here
// (where the `libs` catalog accessor is in scope) and forced per-configuration below.
val kotlinMetadataVersion = libs.versions.kotlin.get()

allprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
            freeCompilerArgs.add("-opt-in=kotlin.ExperimentalUnsignedTypes")
            // -Xannotation-default-target=param-property removed: it's the default since Kotlin 2.4, so the
            // flag is now redundant and the compiler warns about it on every module.
            jvmTarget.set(Versions.jvmTarget)
        }
    }
    gradle.projectsEvaluated {
        tasks.withType<JavaCompile> {
            val compilerArgs = options.compilerArgs
            compilerArgs.add("-Xlint:deprecation")
            compilerArgs.add("-Xlint:unchecked")
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // Only affects configurations that actually pull kotlin-metadata-jvm (the Hilt/Dagger KSP processor
    // classpath), so it's a no-op elsewhere. Keeps the metadata reader in sync with future Kotlin bumps.
    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinMetadataVersion")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Coverage aggregation
//
// Kover (here on the root = the merging project) aggregates UNIT/HOST coverage of the `fullDebug`
// variant across every module that applied `kover-module-dependencies`, into one JaCoCo-format XML
// that Codecov ingests. INSTRUMENTED (connectedAndroidTest) coverage cannot be collected by Kover,
// so it is produced separately by the JaCoCo `jacocoConnectedReport` task (jacoco_connected.gradle.kts)
// and uploaded to Codecov as a second report; Codecov merges the line hits of both.
// ---------------------------------------------------------------------------------------------

// Pull every Kover-enabled module into the merged report as it applies the plugin.
subprojects {
    val subPath = path
    plugins.withId("org.jetbrains.kotlinx.kover") {
        rootProject.dependencies {
            add("kover", project(subPath))
        }
    }
}

kover {
    // Match the engine used by every aggregated module (kover-module-dependencies). The JaCoCo
    // engine records Robolectric coverage out of the box and emits the exact XML Codecov expects.
    useJacoco()

    // The root has no Android variants of its own, so define a custom merged variant that pulls the
    // `fullDebug` report variant from every kover(project()) dependency. optional = true tolerates
    // modules that have no `fullDebug` (e.g. pure-Kotlin/test-less modules). Task produced:
    // koverXmlReportFullDebugAggregated.
    currentProject {
        createVariant("fullDebugAggregated") {
            addWithDependencies("fullDebug", optional = true)
        }
    }

    reports {
        filters {
            excludes {
                // Primary goal of the migration: drop Compose @Preview functions from coverage
                // (JaCoCo cannot filter by annotation; Kover can). @Preview has BINARY retention.
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
                // Generated / boilerplate, ported from the former jacocoAllDebugReport class-file
                // globs to Kover fully-qualified-class-name wildcards.
                classes(
                    "*.di.*Module", "*.di.*Component",
                    "com.jjoe64.*",
                    "*.R", "*.R\$*", "*.R2", "*.R2\$*",
                    "*.BuildConfig",
                    "*.Manifest", "*.Manifest\$*",
                    "*Test*",
                    "*\$ViewInjector*",
                    "*Dagger*",
                    "*MembersInjector", "*MembersInjector\$*",
                    "*_Factory", "*_Provide*Factory*",
                    "*_ViewBinding",
                    "AutoValue_*",
                    "*Directions", "*Directions\$*",
                    "*Binding",
                    "*.BR"
                )
            }
        }
        variant("fullDebugAggregated") {
            xml {
                onCheck = false
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml").get().asFile
            }
        }
    }
}

// Standalone JaCoCo report for instrumented/connected coverage only (the part Kover can't measure).
apply(from = "jacoco_connected.gradle.kts")

tasks.register<Delete>("clean").configure {
    delete(rootProject.layout.buildDirectory)
}
