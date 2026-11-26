package io.github.totocastaldi.herdrdesktop.gradle

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies the [entries] of a zip [archive] (entry path to asset path) into [assets], to be packaged as APK
 * assets. A missing entry fails the build.
 */
abstract class ZipAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty

    @get:Input
    abstract val entries: MapProperty<String, String>

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = assets.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        ZipFile(archive.get().asFile).use { zip ->
            entries.get().forEach { (entry, asset) ->
                val source = zip.getEntry(entry) ?: throw GradleException("$entry not in ${archive.get().asFile.name}")
                val target = out.resolve(asset)
                target.parentFile.mkdirs()
                zip.getInputStream(source).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
