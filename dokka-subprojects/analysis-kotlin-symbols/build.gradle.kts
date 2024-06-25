/*
 * Copyright 2014-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dokkabuild.overridePublicationArtifactId

plugins {
    id("dokkabuild.kotlin-jvm")
    id("dokkabuild.publish-shadow")
}

overridePublicationArtifactId("analysis-kotlin-symbols")

dependencies {
    compileOnly(projects.dokkaSubprojects.dokkaCore)

    // this is a `hack` to include classes `intellij-java-psi-api` in shadowJar
    // which are not present in `kotlin-compiler`
    // should be `api` since we already have it in :analysis-java-psi
    // it's harder to do it in the same as with `fastutil`
    // as several intellij dependencies share the same packages like `org.intellij.core`
    api(libs.intellij.java.psi.api) { isTransitive = false }

    implementation(projects.dokkaSubprojects.analysisKotlinApi)
    implementation(projects.dokkaSubprojects.analysisMarkdownJb)
    implementation(projects.dokkaSubprojects.analysisJavaPsi)

    // ----------- Analysis dependencies ----------------------------------------------------------------------------

    listOf(
        libs.kotlin.high.level.api.api,
        libs.kotlin.analysis.api.standalone,
    ).forEach {
        implementation(it) {
            isTransitive = false // see KTIJ-19820
        }
    }
    listOf(
        libs.kotlin.high.level.api.impl,
        libs.kotlin.high.level.api.fir,
        libs.kotlin.low.level.api.fir,
        libs.kotlin.analysis.api.platform,
        libs.kotlin.symbol.light.classes,
    ).forEach {
        runtimeOnly(it) {
            isTransitive = false // see KTIJ-19820
        }
    }
    // copy-pasted from Analysis API https://github.com/JetBrains/kotlin/blob/a10042f9099e20a656dec3ecf1665eea340a3633/analysis/low-level-api-fir/build.gradle.kts#L37
    runtimeOnly("com.github.ben-manes.caffeine:caffeine:2.9.3")

    runtimeOnly(libs.kotlinx.collections.immutable)
    implementation(libs.kotlin.compiler.k2) {
        isTransitive = false
    }

    // TODO [beresnev] get rid of it
    compileOnly(libs.kotlinx.coroutines.core)
}

tasks.withType<ShadowJar>().configureEach {
    // service files are merged to make sure all Dokka plugins
    // from the dependencies are loaded, and not just a single one.
    mergeServiceFiles()
}

/**
 * hack for shadow jar and fastutil because of kotlin-compiler
 *
 * KT issue: https://youtrack.jetbrains.com/issue/KT-47150
 *
 * what is happening here?
 * 1. we create intermediate `shadowDependenciesJar` with dependencies but without fastutil classes in it
 * 2. then we create final `shadowJar` with full fastutil from maven and dependencies from `shadowDependenciesJar` instead of original dependencies
 *
 * why do we need this?
 *   because `kotlin-compiler` artifact includes unshaded (not-relocated) STRIPPED `fastutil` dependency,
 *   STRIPPED here means, that it doesn't provide full `fastutil` classpath, but only a part of it which is used
 *   and so when shadowJar task is executed it takes classes from `fastutil` from `kotlin-compiler` and adds it to shadow-jar
 *   then adds all other classes from `fastutil` coming from `markdown-jb`,
 *   but because `fastutil` from `kotlin-compiler` is STRIPPED, some classes (like `IntStack`) has no some methods
 *   and so such classes are not replaced afterward by `shadowJar` task - it visits every class once
 *
 */

val shadowOverride: Configuration by configurations.creating {
    description = "dependencies which we need to replace with original ones because `kotlin-compiler` minimizes them"
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
    }
}

dependencies {
    shadowOverride(libs.fastutil)
}

val shadowDependenciesJar by tasks.registering(ShadowJar::class) {
    group = "shadow"
    description = "Create a shadow jar from dependencies without fastutil"

    archiveClassifier = "dependencies"
    destinationDirectory = project.layout.buildDirectory.dir("shadowDependenciesLibs")

    // we need to create JAR with dependencies, but without fastutil,
    // so we include `runtimeClasspath` configuration (the same configuration which is used by default `shadowJar` task)
    // and include `fastutil` from the result
    configurations = listOf(project.configurations.runtimeClasspath.get())
    exclude("it/unimi/dsi/fastutil/**")
}

tasks.shadowJar {
    // override configurations to remove dependencies handled in `shadowJarDependencies`
    configurations = emptyList()
    from(shadowOverride, shadowDependenciesJar)
}
