/**
 * Coverage convention plugin (replaces the former jacoco-module-dependencies /
 * jacoco-app-dependencies). Apply to every module whose code should be measured for coverage.
 *
 * Kover drives coverage instead of the bare `jacoco` Gradle plugin. We keep the JaCoCo *engine*
 * (`useJacoco()`) rather than Kover's default IntelliJ engine because:
 *   - the JaCoCo agent records Robolectric-driven Compose coverage out of the box on this project
 *     (verified), whereas Kover's Robolectric support on the IntelliJ engine is not guaranteed;
 *   - the produced XML is byte-for-byte the JaCoCo format Codecov already ingests, so coverage
 *     numbers stay comparable to the previous setup.
 *
 * The engine MUST match the aggregating root project (which also calls useJacoco()); mixing
 * IntelliJ + JaCoCo across `kover(project())` dependencies is unsupported.
 *
 * NOTE: Kover applies its OWN JaCoCo agent to the unit-test JVM. AGP's `enableUnitTestCoverage`
 * is therefore disabled in the android-*-dependencies convention plugins — two JaCoCo agents on
 * one JVM define java.lang.$JaCoCo twice and crash the test executor. `enableAndroidTestCoverage`
 * stays on: that instruments the on-device test APK (a separate process) and feeds the standalone
 * JaCoCo connected-coverage report, which Kover cannot collect.
 */
plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    useJacoco()

    // The root aggregator merges a CUSTOM report variant named "fullDebugAggregated" across all
    // kover(project()) dependencies. Cross-project aggregation matches on the custom variant name,
    // so every measured module must expose its own "fullDebugAggregated" variant mapping its local
    // `fullDebug` build variant. optional = true tolerates modules that have no `fullDebug`
    // (e.g. pure-Kotlin/no-flavor modules) without failing configuration.
    currentProject {
        createVariant("fullDebugAggregated") {
            add("fullDebug", optional = true)
        }
    }
}
