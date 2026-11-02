import java.io.File
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val releaseSigningFile = providers.environmentVariable("FUNNYWORK_ANDROID_SIGNING_PROPERTIES")
    .map { file(it) }.orNull
val releaseSigning = releaseSigningFile?.let { path ->
    require(path.isFile) { "Release signing configuration not found" }
    Properties().apply { path.inputStream().use { load(it) } }
}
fun signingValue(name: String): String = requireNotNull(releaseSigning?.getProperty(name)?.takeIf { it.isNotBlank() }) {
    "Missing release signing field: $name"
}
val regionalOrigin = providers.environmentVariable("FUNNYWORK_REGIONAL_ORIGIN").orElse("").get()
if (regionalOrigin.isNotEmpty()) {
    val uri = URI(regionalOrigin)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port == -1 &&
        uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
        regionalOrigin == "https://${uri.host.lowercase()}") { "Invalid regional HTTPS origin" }
}
android {
    namespace = "com.funnyworker.android"
    compileSdk = 36
    testBuildType = providers.environmentVariable("FUNNYWORK_ANDROID_TEST_BUILD_TYPE").orElse("debug").get()
    require(testBuildType in setOf("debug", "release"))
    defaultConfig {
        buildConfigField("String", "REGIONAL_ORIGIN", "\"$regionalOrigin\"")
        applicationId = "com.funnyworker.android"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.environmentVariable("FUNNYWORK_ANDROID_VERSION_CODE").orElse("16").get().toInt()
        versionName = "0.1.0-rc.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs.getByName("debug") {
        storeFile = file(providers.environmentVariable("FUNNYWORK_ANDROID_DEBUG_KEYSTORE")
            .orElse(rootDir.resolve("../../.local/agent/packages/android/debug.keystore").absolutePath).get())
    }
    if (releaseSigning != null) {
        signingConfigs.create("distribution") {
            val path = File(signingValue("storeFile"))
            storeFile = if (path.isAbsolute) path else releaseSigningFile!!.parentFile.resolve(path.path)
            require(storeFile!!.isFile) { "Release keystore not found" }
            require(storeFile!!.canonicalFile != signingConfigs.getByName("debug").storeFile?.canonicalFile) {
                "Release signing must use a separate keystore"
            }
            storePassword = signingValue("storePassword")
            keyAlias = signingValue("keyAlias")
            require(keyAlias != "androiddebugkey") { "Debug identity cannot sign a release" }
            keyPassword = signingValue("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug"; resValue("string", "app_name", "FunnyWork Dev") }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    // An explicitly supplied local CA is confined to the debug source set and localhost.
    providers.environmentVariable("FUNNYWORK_ANDROID_DEV_NETWORK").orNull?.let { network ->
        sourceSets.getByName("debug") {
            manifest.srcFile(file("$network/AndroidManifest.xml"))
            res.srcDir(file("$network/res"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; resValues = true; buildConfig = true }
    lint { abortOnError = true }
}
gradle.taskGraph.whenReady {
    check(releaseSigning != null || allTasks.none {
        it.project.path == ":app" && it.name in setOf("packageRelease", "bundleRelease", "packageReleaseBundle")
    }) { "Release packaging requires FUNNYWORK_ANDROID_SIGNING_PROPERTIES; unsigned APK delivery is disabled" }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.10.4")
    implementation("androidx.compose.foundation:foundation:1.10.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.4")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.4")
}
