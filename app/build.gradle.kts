import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vertin.teatalk"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.vertin.teatalk"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.1.9.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            // The single web chat UI lives at the repo root and is packaged into assets.
            assets.srcDir("../web")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += arrayOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.slf4j.simple)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.zxing.core)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}

/**
 * Every debug build gets a versioned, timestamped APK. Existing archives are never
 * overwritten: the name includes versionName + versionCode + local build time.
 */
val archiveVersionedDebugApk = tasks.register("archiveVersionedDebugApk") {
    group = "build"
    description = "Copies the debug APK to a versioned name and archives it without overwriting old builds."
    doLast {
        val source = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        require(source.isFile) { "Debug APK not found: ${source.absolutePath}" }

        val versionName = android.defaultConfig.versionName ?: "dev"
        val versionCode = android.defaultConfig.versionCode ?: 0
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val baseName = "茶室-v${versionName}-vc${versionCode}-${timestamp}-debug"

        val versionedDir = layout.buildDirectory.dir("outputs/apk/debug/versioned").get().asFile
        versionedDir.mkdirs()
        val versioned = File(versionedDir, "$baseName.apk").uniqueFile()
        source.copyTo(versioned, overwrite = false)

        val workspaceRoot = rootProject.projectDir.parentFile?.parentFile ?: rootProject.projectDir
        val archiveDir = File(workspaceRoot, "APK输出/茶室/v${versionName}_vc${versionCode}")
        archiveDir.mkdirs()
        val archived = File(archiveDir, "$baseName.apk").uniqueFile()
        source.copyTo(archived, overwrite = false)

        logger.lifecycle("Versioned APK: ${versioned.absolutePath}")
        logger.lifecycle("Archived APK: ${archived.absolutePath}")
    }
}

tasks.configureEach {
    if (name == "assembleDebug") {
        finalizedBy(archiveVersionedDebugApk)
    }
}

private fun File.uniqueFile(): File {
    if (!exists()) return this
    var index = 2
    while (true) {
        val candidate = File(parentFile, "${nameWithoutExtension}-$index.$extension")
        if (!candidate.exists()) return candidate
        index++
    }
}

