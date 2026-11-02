plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
val output = providers.environmentVariable("FUNNYWORK_ANDROID_BUILD_DIR")
    .orElse(rootDir.resolve("../../.local/agent/apps/android-build").absolutePath).get()
allprojects { layout.buildDirectory.set(file(output).resolve(if (path == ":") "root" else path.removePrefix(":").replace(':', '/'))) }
