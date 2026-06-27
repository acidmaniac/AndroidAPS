apply(plugin = "jacoco")

// Instrumented-only coverage report.
//
// Kover (configured in build.gradle.kts) now owns UNIT/HOST coverage and emits the primary
// JaCoCo-format XML for Codecov. Kover cannot collect on-device (connectedAndroidTest) coverage,
// so this task aggregates ONLY the instrumented execution data (the .ec produced by AGP's
// enableAndroidTestCoverage) into a second JaCoCo XML. CircleCI uploads both XMLs and Codecov
// merges their line hits, preserving the combined unit + instrumented coverage number.
//
// Note: JaCoCo cannot filter by annotation, so @Preview functions are NOT excluded from THIS
// report. They re-enter the denominator only for classes the (small) instrumented suite loads;
// the unit/host report (the bulk) has them excluded via Kover's annotatedBy filter.
project.afterEvaluate {
    val variants = listOf("fullDebug")

    tasks.register<JacocoReport>(name = "jacocoConnectedReport") {

        group = "Reporting"
        description = "Aggregate JaCoCo coverage for instrumented (connectedAndroidTest) tests only."

        reports {
            html.required.set(true)
            xml.required.set(true)
            xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacoco_connected.xml"))
        }

        val excludes = listOf(
            "**/di/*Module.class",
            "**/di/*Component.class",
            "**/com/jjoe64/**/*.*",
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            "androidx/**/*.*",
            "**/*\$ViewInjector*.*",
            "**/*Dagger*.*",
            "**/*MembersInjector*.*",
            "**/*_Factory.*",
            "**/*_Provide*Factory*.*",
            "**/*_ViewBinding*.*",
            "**/AutoValue_*.*",
            "**/R2.class",
            "**/R2$*.class",
            "**/*Directions$*",
            "**/*Directions.*",
            "**/*Binding.*",
            "**/BR.class"
        )

        val classes = HashSet<ConfigurableFileTree>()
        subprojects.forEach { proj ->
            variants.forEach { variant ->
                // Use variant directory as base - fileTree recurses into subdirectories
                // This avoids hardcoding exact task-name subdirectories that may vary between AGP versions
                val javaPath = proj.layout.buildDirectory.dir("intermediates/javac/$variant").get()
                classes.add(fileTree(javaPath) { exclude(excludes); include("**/*.class") })
                val kotlinPath = proj.layout.buildDirectory.dir("intermediates/built_in_kotlinc/$variant").get()
                classes.add(fileTree(kotlinPath) { exclude(excludes); include("**/*.class") })
                // Fallback for older AGP versions
                val kotlinLegacyPath = proj.layout.buildDirectory.dir("tmp/kotlin-classes/$variant").get()
                classes.add(fileTree(kotlinLegacyPath) { exclude(excludes); include("**/*.class") })
            }
        }
        classDirectories.setFrom(files(listOf(classes)))

        val sources = mutableListOf<String>().also {
            subprojects.forEach { proj ->
                variants.forEach { variant ->
                    it.add("${proj.projectDir}/src/main/java")
                    it.add("${proj.projectDir}/src/main/kotlin")
                    it.add("${proj.projectDir}/src/$variant/java")
                    it.add("${proj.projectDir}/src/$variant/kotlin")
                }
            }
        }
        sourceDirectories.setFrom(files(sources))

        // INSTRUMENTED (connected) execution data ONLY. The unit-test .exec branch from the former
        // jacoco_aggregation.gradle.kts is intentionally dropped — Kover covers unit/host tests.
        val executions = mutableListOf<File>()
        subprojects.forEach { proj ->
            variants.forEach { variant ->
                val androidPath = proj.layout.buildDirectory.dir("outputs/code_coverage/${variant}AndroidTest/connected/").get()
                fileTree(androidPath).forEach { file ->
                    println("Collecting android execution data from: ${file.absolutePath}")
                    executions.add(file)
                }
            }
        }
        executionData.setFrom(executions)
    }
}
