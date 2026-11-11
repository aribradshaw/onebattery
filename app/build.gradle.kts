import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: read from the gitignored keystore.properties in the project root
// (storeFile, storePassword, keyAlias, keyPassword). When it is absent, or the build is run
// with -PdebugSigning, release builds fall back to the debug key so local builds keep working.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val useReleaseKey = keystorePropsFile.isFile && !project.hasProperty("debugSigning")

// Bradshaw Versioning (YEAR.MONTH.UPDATE) from version.properties, written by scripts/devlog-release.mjs.
// versionCode = YEAR*1_000_000 + MONTH*10_000 + UPDATE (1.3.6 -> 1030006, 1.4.9 -> 1040009, 2.1.1 -> 2010001).
// MONTH <= 12 and UPDATE <= 9999 keep MONTH*10_000 + UPDATE < 1_000_000, so the code always increases,
// including across the year-1 (Aug = 0 … Dec = 4) to year-2 (Jan = 1) boundary. Valid up to year 2100.
val bradshawVersion: String = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("version")?.trim() ?: error("version.properties has no version=")
val bradshawVersionCode: Int = bradshawVersion.split(".").map { it.toInt() }.let { (y, m, u) ->
    require(y >= 1 && m in 0..12 && u in 1..9_999) { "Bad Bradshaw version $bradshawVersion" }
    y * 1_000_000 + m * 10_000 + u
}

// Bundles the DevLog registry into the APK as assets/devlog.json (read by ui/DevlogScreen.kt).
abstract class DevlogAssetTask : DefaultTask() {
    @get:InputFile abstract val registry: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun copy() {
        registry.get().asFile.copyTo(outputDir.get().file("devlog.json").asFile, overwrite = true)
    }
}
val devlogAssets = tasks.register<DevlogAssetTask>("devlogAssets") {
    registry.set(rootProject.layout.projectDirectory.file("config/devlog-releases.json"))
}

android {
    namespace = "com.flygonlc.onebattery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flygonlc.onebattery"
        minSdk = 29
        targetSdk = 36
        // Bradshaw Versioning: single source is version.properties (see docs/VERSIONING.md).
        versionCode = bradshawVersionCode
        versionName = bradshawVersion
    }

    signingConfigs {
        if (useReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Upload key from keystore.properties for Play; debug key otherwise (sideloadable).
            signingConfig = signingConfigs.getByName(if (useReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

// Compose compiler stability reports, only when asked: ./gradlew :app:assembleRelease -PcomposeReports
// (written to app/build/compose_compiler/).
if (project.hasProperty("composeReports")) {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(devlogAssets, DevlogAssetTask::outputDir)
    }
}

dependencies {
    // 2025.09.00: Compose UI/foundation 1.9.1 (Modifier.preferredFrameRate for 120 Hz voting on API 35+),
    // Material3 stays on 1.3.2 so components look and behave exactly as before.
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Installs the bundled baseline profile (src/main/baseline-prof.txt) on sideloads, so startup,
    // the dashboard, charts and settings run AOT-compiled instead of interpreted on first launches.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
