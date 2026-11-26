package io.github.totocastaldi.herdrdesktop.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Unpacks a `.tar.gz` or `.tar.xz` [archive] into an empty [destination] with the host `tar`,
 * which also handles the xz compression that Gradle's `tarTree` does not read.
 */
abstract class ExtractTarball : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun extract() {
        val dir = destination.get().asFile
        fs.delete { delete(dir) }
        dir.mkdirs()
        exec.exec {
            commandLine("tar", "-xf", archive.get().asFile.absolutePath, "-C", dir.absolutePath)
        }
    }
}
