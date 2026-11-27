import io.github.totocastaldi.herdrdesktop.gradle.LegalAssets
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The app license and the licenses of the third-party code, packaged as assets/LICENSE and assets/NOTICE.
val legalAssets = tasks.register<LegalAssets>("legalAssets") {
    files.from(rootProject.layout.projectDirectory.files("LICENSE", "NOTICE"))
}

// Signing of the dogfood build: a properties file outside the repository (storeFile, storePassword, keyAlias,
// keyPassword), named by the Gradle property herdr.dogfood.signing, e.g. in ~/.gradle/gradle.properties.
val dogfoodSigningFile: File? =
    providers.gradleProperty("herdr.dogfood.signing").orNull?.let { file(it) }?.takeIf { it.isFile }
val dogfoodSigning: Properties? =
    dogfoodSigningFile?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssets::assets)
    }
}

android {
    namespace = "io.github.totocastaldi.herdrdesktop"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "io.github.totocastaldi.herdrdesktop"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (dogfoodSigning != null) {
            create("dogfood") {
                storeFile = dogfoodSigningFile!!.resolveSibling(dogfoodSigning.getProperty("storeFile"))
                storePassword = dogfoodSigning.getProperty("storePassword")
                keyAlias = dogfoodSigning.getProperty("keyAlias")
                keyPassword = dogfoodSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Optimized, signed build installed beside the debug one, so workers can reinstall and test the debug app
        // while the user keeps working with this one (own application id, name and icon).
        create("dogfood") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dogfood"
            versionNameSuffix = "-dogfood"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += "release"
            signingConfig = signingConfigs.findByName("dogfood")
        }
    }

    buildFeatures {
        compose = true
    }
}

// Without the signing key the dogfood build stops with a clear message; debug builds and the gate are unaffected.
if (dogfoodSigning == null) {
    tasks.matching { it.name == "preDogfoodBuild" }.configureEach {
        doFirst {
            throw GradleException(
                "Dogfood signing not configured: set herdr.dogfood.signing to the signing properties file (README)."
            )
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(project(":core-client"))
    implementation(project(":terminal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
