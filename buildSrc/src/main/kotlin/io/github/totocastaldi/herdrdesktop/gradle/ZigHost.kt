package io.github.totocastaldi.herdrdesktop.gradle

/** Zig release for the build host: [name] as in Zig's file names, [catalogKey] for its checksum. */
data class ZigHost(val name: String, val catalogKey: String) {
    companion object {
        fun current(): ZigHost {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val zigOs = when {
                os.contains("linux") -> "linux"
                os.contains("mac") -> "macos"
                else -> error("Unsupported build host for the native terminal build: $os")
            }
            val (zigArch, family) = when (arch) {
                "amd64", "x86_64" -> "x86_64" to "intel"
                "aarch64", "arm64" -> "aarch64" to "arm"
                else -> error("Unsupported build host architecture for the native terminal build: $arch")
            }
            return ZigHost(name = "$zigArch-$zigOs", catalogKey = "zig-sha256-$zigOs-$family")
        }
    }
}
