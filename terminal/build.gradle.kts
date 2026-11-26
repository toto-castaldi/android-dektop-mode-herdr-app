import io.github.totocastaldi.herdrdesktop.gradle.ExtractTarball
import io.github.totocastaldi.herdrdesktop.gradle.VerifiedDownload
import io.github.totocastaldi.herdrdesktop.gradle.ZigBuildLibGhosttyVt
import io.github.totocastaldi.herdrdesktop.gradle.ZigHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(name: String): String = catalog.findVersion(name).get().requiredVersion

// Native build of libghostty-vt (ADR-001, docs/design/configuration.md).
val ghosttyVersion = catalogVersion("libghostty-vt")
val ghosttyAndroidApi = catalogVersion("libghostty-vt-android-api")
val zigVersion = catalogVersion("zig")
val zigHost = ZigHost.current()
val downloadCache = gradle.gradleUserHomeDir.resolve("caches/herdr-desktop/downloads")
val nativeDir = layout.buildDirectory.dir("libghostty-vt")
val ghosttyPrefix = nativeDir.map { it.dir("prefix") }
val ghosttyReleaseUrl = "https://release.files.ghostty.org/libghostty-vt/$ghosttyVersion"

val downloadZig = tasks.register<VerifiedDownload>("downloadZig") {
    val file = "zig-${zigHost.name}-$zigVersion.tar.xz"
    url.set("https://ziglang.org/download/$zigVersion/$file")
    sha256.set(catalogVersion(zigHost.catalogKey))
    output.set(downloadCache.resolve(file))
}

val downloadGhosttySources = tasks.register<VerifiedDownload>("downloadGhosttySources") {
    val file = "libghostty-vt-$ghosttyVersion.tar.gz"
    url.set("$ghosttyReleaseUrl/$file")
    sha256.set(catalogVersion("libghostty-vt-sha256"))
    output.set(downloadCache.resolve(file))
}

val downloadGhosttyDependencies = tasks.register<VerifiedDownload>("downloadGhosttyDependencies") {
    val file = "libghostty-vt-$ghosttyVersion-deps.tar.gz"
    url.set("$ghosttyReleaseUrl/$file")
    sha256.set(catalogVersion("libghostty-vt-deps-sha256"))
    output.set(downloadCache.resolve(file))
}

val extractZig = tasks.register<ExtractTarball>("extractZig") {
    archive.set(downloadZig.flatMap { it.output })
    destination.set(nativeDir.map { it.dir("zig") })
}

val extractGhosttySources = tasks.register<ExtractTarball>("extractGhosttySources") {
    archive.set(downloadGhosttySources.flatMap { it.output })
    destination.set(nativeDir.map { it.dir("src") })
}

val extractGhosttyDependencies = tasks.register<ExtractTarball>("extractGhosttyDependencies") {
    archive.set(downloadGhosttyDependencies.flatMap { it.output })
    destination.set(nativeDir.map { it.dir("deps") })
}

val buildLibGhosttyVt = tasks.register<ZigBuildLibGhosttyVt>("buildLibGhosttyVt") {
    description = "Builds libghostty-vt $ghosttyVersion for arm64-v8a with Zig $zigVersion."
    zigHome.set(extractZig.flatMap { it.destination })
    sources.set(extractGhosttySources.flatMap { it.destination })
    dependencies.set(extractGhosttyDependencies.flatMap { it.destination })
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    ndkVersion.set(libs.versions.android.ndk)
    target.set("aarch64-linux-android.$ghosttyAndroidApi")
    optimize.set("ReleaseFast")
    cacheDirectory.set(nativeDir.map { it.dir("zig-cache") })
    prefix.set(ghosttyPrefix)
}

android {
    namespace = "io.github.totocastaldi.herdrdesktop.terminal"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
        }
    }
    ndkVersion = libs.versions.android.ndk.get()

    buildFeatures {
        compose = true
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DGHOSTTY_VT_PREFIX=${ghosttyPrefix.get().asFile.absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.android.cmake.get()
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(buildLibGhosttyVt)
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
