// Emulator smoke tests (docs/HARDENING.md, Testing; run with tools/emulator/smoke.sh). A separate, self-instrumenting test APK:
// instrumentation inside :app would run Sortie in an instrumented process, and Android never starts an instrumented HOME app on
// a secondary display by itself (RootWindowContainer.canStartHomeOnDisplayArea), which is the fallback these tests check. The
// tests see Sortie only as a player does, through the shell, the task list and the accessibility tree.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.esko997.sortie.smoke"
    compileSdk = 35
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
