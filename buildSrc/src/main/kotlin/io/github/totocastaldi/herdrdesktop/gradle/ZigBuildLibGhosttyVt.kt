package io.github.totocastaldi.herdrdesktop.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Builds libghostty-vt from its release source tarball with Zig, offline, following the
 * library's `PACKAGING-LIBGHOSTTY.md`: the Zig packages come only from [dependencies] and the
 * global Zig cache starts empty. Installs the static library and the headers in [prefix].
 */
abstract class ZigBuildLibGhosttyVt : DefaultTask() {
    /** Unpacked Zig distribution, the directory that holds the `zig` executable. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val zigHome: DirectoryProperty

    /** Unpacked `libghostty-vt-VERSION` source directory. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    /** Unpacked `libghostty-vt-VERSION-deps` directory. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencies: DirectoryProperty

    /** Android NDK used for the bionic headers and libraries. */
    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    /** NDK version, the input that stands for [ndkDirectory]. */
    @get:Input
    abstract val ndkVersion: Property<String>

    /** Zig target triple, for example `aarch64-linux-android.35`. */
    @get:Input
    abstract val target: Property<String>

    @get:Input
    abstract val optimize: Property<String>

    /** Scratch directory for the Zig caches, emptied before every build. */
    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val prefix: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun build() {
        val source = singleChild(sources.get().asFile)
        val deps = singleChild(dependencies.get().asFile)
        val zig = singleChild(zigHome.get().asFile).resolve("zig")
        val cache = cacheDirectory.get().asFile
        val out = prefix.get().asFile
        fs.delete {
            delete(cache, out, source.resolve(".zig-cache"))
        }
        cache.mkdirs()
        exec.exec {
            workingDir = source
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
            environment("ZIG_GLOBAL_CACHE_DIR", cache.resolve("global").absolutePath)
            environment("ZIG_LOCAL_CACHE_DIR", cache.resolve("local").absolutePath)
            commandLine(
                zig.absolutePath,
                "build",
                "--prefix",
                out.absolutePath,
                "--system",
                deps.absolutePath,
                "-Dtarget=${target.get()}",
                "-Doptimize=${optimize.get()}",
                "-Dcpu=baseline",
                "-Dstrip=true"
            )
        }
    }

    private fun singleChild(dir: java.io.File): java.io.File =
        dir.listFiles()?.singleOrNull { it.isDirectory }
            ?: error("Expected exactly one directory in $dir")
}
