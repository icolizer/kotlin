/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.yarn

import com.github.gundy.semver4j.SemVer
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.internal.execWithProgress
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmApi
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmDependency
import org.jetbrains.kotlin.gradle.targets.js.npm.resolved.KotlinCompilationNpmResolution
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLock.Companion.dependencyKey
import java.io.File

abstract class YarnBasics : NpmApi {

    private val nonTransitiveResolvedDependencies = mutableMapOf<NpmDependency, Set<File>>()
    private val transitiveResolvedDependencies = mutableMapOf<NpmDependency, Set<File>>()

    override fun setup(project: Project) {
        YarnPlugin.apply(project).executeSetup()
    }

    fun yarnExec(
        project: Project,
        dir: File,
        description: String,
        vararg args: String
    ) {
        val nodeJs = NodeJsRootPlugin.apply(project)
        val yarnPlugin = YarnPlugin.apply(project)

        project.execWithProgress(description) { exec ->
            exec.executable = nodeJs.requireConfigured().nodeExecutable
            exec.args = listOf(yarnPlugin.requireConfigured().home.resolve("bin/yarn.js").absolutePath) +
                    args +
                    if (project.logger.isDebugEnabled) "--verbose" else ""
            exec.workingDir = dir
        }

    }

    override fun resolveDependency(
        npmResolution: KotlinCompilationNpmResolution,
        dependency: NpmDependency,
        transitive: Boolean
    ): Set<File> {
        val files = (if (transitive) {
            transitiveResolvedDependencies
        } else {
            nonTransitiveResolvedDependencies
        })[dependency]

        if (files != null) {
            return files
        }

        val npmProject = npmResolution.npmProject

        val all = mutableSetOf<File>()

        npmProject.resolve(dependency.key)?.let {
            if (it.isFile) all.add(it)
            if (it.path.endsWith(".js")) {
                val baseName = it.path.removeSuffix(".js")
                val metaJs = File(baseName + ".meta.js")
                if (metaJs.isFile) all.add(metaJs)
                val kjsmDir = File(baseName)
                if (kjsmDir.isDirectory) {
                    kjsmDir.walkTopDown()
                        .filter { it.extension == "kjsm" }
                        .forEach { all.add(it) }
                }
            }
        }

        nonTransitiveResolvedDependencies[dependency] = all

        if (transitive) {
            dependency.dependencies.forEach {
                resolveDependency(
                    npmResolution,
                    it,
                    transitive
                ).also { files ->
                    all.addAll(files)
                }
            }
            transitiveResolvedDependencies[dependency] = all
        }

        return all
    }

    protected fun yarnLockReadTransitiveDependencies(
        nodeWorkDir: File,
        srcDependenciesList: Collection<NpmDependency>
    ) {
        val yarnLock = nodeWorkDir
            .resolve("yarn.lock")
            .takeIf { it.isFile }
            ?: return

        val entryRegistry = YarnEntryRegistry(yarnLock)
        val visited = mutableSetOf<NpmDependency>()

        fun resolveRecursively(src: NpmDependency): NpmDependency {
            if (src in visited) {
                return src
            }
            visited.add(src)

            val deps = entryRegistry.find(src.key, src.version)

            src.resolvedVersion = deps.version
            src.integrity = deps.integrity

            deps.dependencies.mapTo(src.dependencies) { dep ->
                val scopedName = dep.scopedName
                val child = NpmDependency(
                    project = src.project,
                    name = scopedName.toString(),
                    version = dep.version ?: "*"
                )
                child.parent = src

                resolveRecursively(child)

                child
            }

            return src
        }

        srcDependenciesList.forEach { src ->
            resolveRecursively(src)
        }
    }
}

private class YarnEntryRegistry(lockFile: File) {
    val entryMap = YarnLock.parse(lockFile)
        .entries
        .associateBy { it.dependencyKey }

    fun find(packageKey: String, version: String): YarnLock.Entry {
        val key = dependencyKey(packageKey, version)
        var entry = entryMap[key]

        if (entry == null && version == "*") {
            val searchKey = dependencyKey(packageKey, "")
            entry = entryMap.entries
                .filter { it.key.startsWith(searchKey) }
                .firstOrNull {
                    SemVer.satisfies(it.key.removePrefix(searchKey), "*")
                }
                ?.value
        }

        return checkNotNull(entry) {
            "Cannot find $key in yarn.lock"
        }
    }
}
