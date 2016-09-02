/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.script.lang.kotlin.provider

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.SelfResolvingDependency

import org.gradle.api.internal.ClassPathRegistry
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactory

import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath

import org.gradle.script.lang.kotlin.codegen.ActionExtensionWriter
import org.gradle.script.lang.kotlin.codegen.classNodeFor
import org.gradle.script.lang.kotlin.codegen.isApiClassEntry
import org.gradle.script.lang.kotlin.codegen.conflictsWithExtension
import org.gradle.script.lang.kotlin.codegen.forEachZipEntryIn
import org.gradle.script.lang.kotlin.loggerFor
import org.gradle.script.lang.kotlin.support.asm.removeMethodsMatching
import org.gradle.script.lang.kotlin.support.zipTo

import org.gradle.util.GFileUtils.moveFile

import org.jetbrains.kotlin.utils.addToStdlib.singletonList

import java.io.File

typealias JarCache = (String, JarGenerator) -> File

typealias JarGenerator = (File) -> Unit

class KotlinScriptClassPathProvider(
    val classPathRegistry: ClassPathRegistry,
    val dependencyFactory: DependencyFactory,
    val jarCache: JarCache) {

    /**
     * Generated Gradle API jar plus supporting libraries such as groovy-all.jar.
     */
    val gradleApi: ClassPath by lazy {
        DefaultClassPath.of(gradleApiFiles())
    }

    /**
     * Generated extensions to the Gradle API.
     */
    val gradleApiExtensions: ClassPath by lazy {
        gradleApi.filter { it.name.startsWith("gradle-script-kotlin-extensions-") }
    }

    /**
     * gradle-script-kotlin.jar plus kotlin libraries.
     */
    val gradleScriptKotlinJars: ClassPath by lazy {
        DefaultClassPath.of(gradleScriptKotlinJarsFrom(classPathRegistry))
    }

    /**
     * Returns the generated Gradle API jar plus supporting files such as groovy-all.jar.
     */
    private fun gradleApiFiles() =
        gradleApiDependency().resolve().flatMap {
            when {
                it.name.startsWith("gradle-api-") ->
                    listOf(
                        kotlinGradleApiFrom(it),
                        kotlinGradleApiExtensionsFrom(it))
                else ->
                    it.singletonList()
            }
        }

    private fun kotlinGradleApiFrom(gradleApiJar: File): File =
        produce("script-kotlin-api") { outputFile ->
            generateKotlinGradleApiTo(outputFile, gradleApiJar)
        }

    private fun kotlinGradleApiExtensionsFrom(gradleApiJar: File): File =
        produce("script-kotlin-extensions") { outputFile ->
            val tempDir = tempDirFor(outputFile)
            compileExtensionsTo(tempDir, gradleApiJar)
            zipTo(outputFile, tempDir)
        }

    private fun generateKotlinGradleApiTo(outputFile: File, gradleApiJar: File) {
        gradleApiJar.inputStream().use { input ->
            outputFile.outputStream().use { output ->
                removeMethodsMatching(
                    ::conflictsWithExtension,
                    input.buffered(),
                    output.buffered(),
                    shouldTransformEntry = { isApiClassEntry() })
            }
        }
    }

    private fun compileExtensionsTo(outputDir: File, gradleApiJar: File) {
        val sourceFile = File(outputDir, extensionsSourceFileName())
        writeActionExtensionsTo(sourceFile, gradleApiJar)
        compileToDirectory(
            outputDir,
            sourceFile,
            loggerFor<KotlinScriptClassPathProvider>(),
            classPath = listOf(gradleApiJar))
    }

    private fun extensionsSourceFileName() =
        ActionExtensionWriter.packageName.replace('.', '/') + "/ActionExtensions.kt"

    private fun writeActionExtensionsTo(kotlinFile: File, gradleApiJar: File) {
        kotlinFile.apply { parentFile.mkdirs() }.bufferedWriter().use { writer ->
            val extensionWriter = ActionExtensionWriter(writer)
            forEachZipEntryIn(gradleApiJar) {
                if (isApiClassEntry()) {
                    val classNode = classNodeFor(zipInputStream)
                    extensionWriter.writeExtensionsFor(classNode)
                }
            }
        }
    }

    private fun produce(id: String, generate: JarGenerator): File =
        jarCache(id) { outputFile ->
            generateAtomically(outputFile, generate)
        }

    private fun generateAtomically(outputFile: File, generate: JarGenerator) {
        val tempFile = tempFileFor(outputFile)
        generate(tempFile)
        moveFile(tempFile, outputFile)
    }

    private fun tempFileFor(outputFile: File): File =
        createTempFile(outputFile.nameWithoutExtension, outputFile.extension).apply {
            deleteOnExit()
        }

    private fun tempDirFor(outputFile: File): File =
        createTempDir(outputFile.nameWithoutExtension, outputFile.extension).apply {
            deleteOnExit()
        }

    private fun gradleApiDependency() =
        (dependencyFactory.gradleApi() as SelfResolvingDependency)
}

fun gradleScriptKotlinJarsFrom(classPathRegistry: ClassPathRegistry): List<File> =
    classPathRegistry.gradleJars().filter {
        it.name.let { isKotlinJar(it) || it.startsWith("gradle-script-kotlin-") }
    }

fun ClassPathRegistry.gradleJars(): Collection<File> =
    getClassPath(gradleApiNotation.name).asFiles

fun DependencyFactory.gradleApi(): Dependency =
    createDependency(gradleApiNotation)

private val gradleApiNotation = DependencyFactory.ClassPathNotation.GRADLE_API

// TODO: make the predicate more precise
fun isKotlinJar(name: String): Boolean =
    name.startsWith("kotlin-stdlib-")
        || name.startsWith("kotlin-reflect-")
        || name.startsWith("kotlin-runtime-")
