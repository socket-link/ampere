plugins {
    kotlin("multiplatform").apply(false)
    kotlin("plugin.serialization").apply(false)
    kotlin("plugin.compose").apply(false)
    id("com.android.application").apply(false)
    id("com.android.library").apply(false)
    id("org.jetbrains.compose").apply(false)
    id("app.cash.sqldelight").apply(false)
    id("org.jlleitschuh.gradle.ktlint").version("12.2.0").apply(false)
    id("com.vanniktech.maven.publish").apply(false)
}

allprojects {
    tasks.withType<Test>().configureEach {
        maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}

// AMPR-271: ampere-core-test-fixtures was configured with `mavenPublishing { ... }` — so it
// looked published — but the publish workflow's actual `./gradlew ...` command line never
// mentioned it, so it silently never reached Maven Central across two releases (0.12.0,
// 0.13.0). "Declared for publishing" and "listed in the CI publish step" are two different
// facts that can drift apart with no build failure to catch it. This task closes that gap:
// it fails the build if any subproject with a `mavenPublishing {` block is missing from the
// publish/dry-run steps in .github/workflows/publish.yml, so the next module that opts into
// publishing can't fall out of releases the same way.
val verifyPublishWorkflowCoverage = tasks.register("verifyPublishWorkflowCoverage") {
    group = "verification"
    description = "Fails if a module configured for Maven Central publishing is missing from " +
        "the publish step in .github/workflows/publish.yml (AMPR-271)."

    val workflowFile = layout.projectDirectory.file(".github/workflows/publish.yml")
    val subprojectBuildFiles = subprojects
        .map { it.path to it.projectDir.resolve("build.gradle.kts") }
        .filter { (_, file) -> file.exists() }

    inputs.file(workflowFile)
    inputs.files(subprojectBuildFiles.map { it.second })

    doLast {
        val workflowText = workflowFile.asFile.readText()
        val publishedModules = subprojectBuildFiles
            .filter { (_, file) -> file.readText().contains("mavenPublishing {") }
            .map { (path, _) -> path }

        val missing = publishedModules.filter { path ->
            !workflowText.contains("$path:publishAllPublicationsToMavenCentralRepository")
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "The following modules configure Maven Central publishing " +
                    "(mavenPublishing { ... } in their build.gradle.kts) but are missing from " +
                    "the publish step(s) in .github/workflows/publish.yml:\n" +
                    missing.joinToString("\n") { "  - $it" } +
                    "\n\nAdd them to the 'Publish to Maven Central' and 'Dry run publish' run " +
                    "commands, or the artifact will silently never reach Central (AMPR-271).",
            )
        }
    }
}
