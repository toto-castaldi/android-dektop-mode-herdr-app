package io.github.totocastaldi.herdrdesktop.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Copies the repository [notice] into [assets] as `NOTICE`, packaged as an APK asset. */
abstract class NoticeAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = assets.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        notice.get().asFile.copyTo(out.resolve("NOTICE"))
    }
}
