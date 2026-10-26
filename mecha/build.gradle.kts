// The experimental Hangar companion: its rules, app-private save, parts catalog and art (assets/mecha/), roaming (stage,
// spots, one mech across displays) and the Hangar UI. Host-neutral: the app supplies play sessions (PlaySession), hosts
// stages and declares spots.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.esko997.sortie.mecha"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isReturnDefaultValues = true // android.util.Log in MechaStore under plain JUnit
    buildFeatures {
        compose = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":design"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test) // the companion's lifecycle gate on virtual time
    testDebugImplementation(libs.robolectric) // native BitmapFactory checks for authored art and finish pixels
}
