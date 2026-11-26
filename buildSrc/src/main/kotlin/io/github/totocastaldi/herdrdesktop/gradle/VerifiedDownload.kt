package io.github.totocastaldi.herdrdesktop.gradle

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Downloads [url] to [output] and fails the build unless the file has the SHA-256 [sha256].
 *
 * An existing [output] with the right checksum is reused, so the file can live in a cache shared
 * by several builds; a file with a wrong checksum is deleted, never used.
 */
abstract class VerifiedDownload : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun download() {
        val target = output.get().asFile
        val expected = sha256.get().lowercase()
        if (target.isFile && sha256Of(target) == expected) return

        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
        try {
            URI(url.get()).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256Of(partial)
            if (actual != expected) {
                throw GradleException(
                    "Checksum mismatch for ${url.get()}: expected SHA-256 $expected, got $actual. " +
                        "Update the version catalog only after checking the new file."
                )
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_SIZE = 1 shl 16
    }
}
