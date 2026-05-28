import java.util.Properties
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

// Reads the highest semver tag from `git tag`. Used by debug builds so the
// installed APK reflects the actual latest release (CI strips/injects versionName
// for release builds — debug runs locally without that injection, so we derive
// it from git here). Fails open to the defaultConfig versionName if git is
// unavailable.
fun gitLatestSemverTag(): String? = try {
    val proc = ProcessBuilder("git", "tag", "--list", "v*", "--sort=-version:refname")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    if (proc.waitFor(2, TimeUnit.SECONDS)) {
        proc.inputStream.bufferedReader().readLines()
            .map { it.trim().removePrefix("v") }
            .firstOrNull { it.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
    } else {
        proc.destroyForcibly(); null
    }
} catch (_: Throwable) { null }

// Total commit count — used as debug versionCode so each rebuild after pulling
// new commits gets a higher code than any previously installed debug build.
fun gitCommitCount(): Int = try {
    val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    if (proc.waitFor(2, TimeUnit.SECONDS)) {
        proc.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
    } else {
        proc.destroyForcibly(); 0
    }
} catch (_: Throwable) { 0 }

android {
    namespace = "com.castla.mirror"
    // Raised compileSdk to 36 to satisfy requirement of androidx.core:core:1.18.0 and other dependencies
    compileSdk = 36

    if (keystorePropertiesFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.castla.mirror"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "2.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // versionNameSuffix intentionally not set here — overridden via
            // androidComponents.onVariants below so the debug name follows the
            // current git tag rather than the stub defaultConfig.versionName.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.api.ApkVariantOutput
            val dateStr = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
            output.outputFileName = "castla-${variant.versionName}-$dateStr.apk"
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val commitCount = gitCommitCount()
        variant.outputs.forEach { output ->
            val nameProvider = output.versionName
            val codeProvider = output.versionCode
            
            val baseVersion = android.defaultConfig.versionName ?: "1.0.0"
            nameProvider.set("$baseVersion-debug")
            
            if (commitCount > 0) {
                codeProvider.set(commitCount)
            }
        }
    }
}

dependencies {
    // NanoHTTPD (HTTP + WebSocket server)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")

    // Android Instrumented Tests
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

val frontendDistDir = rootProject.layout.projectDirectory.dir("frontend/dist")
val frontendDir = rootProject.layout.projectDirectory.dir("frontend")
val embeddedWebDir = layout.projectDirectory.dir("src/main/assets/web")

fun pnpmCommand(vararg args: String): List<String> {
    return if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        listOf("cmd", "/c", "pnpm") + args
    } else {
        listOf("pnpm") + args
    }
}

tasks.register<Exec>("pnpmInstallFrontend") {
    onlyIf { frontendDir.asFile.exists() }
    workingDir = frontendDir.asFile
    commandLine(pnpmCommand("install", "--frozen-lockfile"))
}

tasks.register<Exec>("buildFrontend") {
    onlyIf { frontendDir.asFile.exists() }
    dependsOn("pnpmInstallFrontend")
    workingDir = frontendDir.asFile
    commandLine(pnpmCommand("run", "build"))
}

tasks.register<Sync>("copyFrontendDistToAssets") {
    dependsOn("buildFrontend")
    from(frontendDistDir)
    into(embeddedWebDir)
}

tasks.named("preBuild") {
    dependsOn("copyFrontendDistToAssets")
}
