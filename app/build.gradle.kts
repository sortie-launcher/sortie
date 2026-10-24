import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.esko997.sortie"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.esko997.sortie"
        minSdk = 30 // MANAGE_EXTERNAL_STORAGE exists from API 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isReturnDefaultValues = true // android.util.Log in UserStore under plain JUnit
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Bundled magazines (tools/publish-magazines.py writes assets/magazines/ and its catalog.json): every build checks that the
// packaged files are exactly the catalogued ones, byte for byte, so a hand edit or half copy cannot ship. Up to date
// (skipped) while the folder is unchanged; no folder means no bundled issues.
val verifyMagazineAssets by tasks.registering {
    description = "Checks src/main/assets/magazines against its catalog.json"
    val dir = layout.projectDirectory.dir("src/main/assets/magazines").asFile
    val marker = layout.buildDirectory.file("magazine-assets/verified.txt")
    inputs.files(fileTree(dir)).withPropertyName("magazines")
    outputs.file(marker)
    doLast {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val out = marker.get().asFile
        out.parentFile.mkdirs()
        if (!dir.isDirectory) {
            out.writeText("no bundled magazines\n")
            return@doLast
        }
        val catalogFile = File(dir, "catalog.json")
        check(catalogFile.isFile) { "${dir.path} has no catalog.json; publish with tools/publish-magazines.py" }
        val catalog = groovy.json.JsonSlurper().parse(catalogFile) as Map<*, *>
        check(catalog["format"] == 1) { "magazine catalog: unknown format ${catalog["format"]}" }
        val listed = mutableSetOf("catalog.json")
        val issueLines = (catalog["issues"] as List<*>).map { raw ->
            val issue = raw as Map<*, *>
            val id = issue["id"] as String
            val revision = (issue["revision"] as Number).toInt()
            val fileLines = (issue["files"] as List<*>).map { f ->
                val entry = f as Map<*, *>
                val path = entry["path"] as String
                val file = File(dir, "$id/$path")
                check(file.isFile) { "magazine $id: catalogued $path is missing" }
                val bytes = file.readBytes()
                check(bytes.size.toLong() == (entry["size"] as Number).toLong() && sha256(bytes) == entry["sha256"]) {
                    "magazine $id: $path differs from the catalog; republish with tools/publish-magazines.py"
                }
                listed += "$id/$path"
                "$path\t${bytes.size}\t${entry["sha256"]}"
            }
            val digest = sha256(fileLines.joinToString("\n").toByteArray())
            check(digest == issue["digest"]) { "magazine $id: issue digest does not match its files" }
            val json = groovy.json.JsonSlurper().parse(File(dir, "$id/issue.json")) as Map<*, *>
            check(json["schemaVersion"] == 2 && json["id"] == id && (json["revision"] as? Number)?.toInt() == revision) {
                "magazine $id: issue.json must be schemaVersion 2 with id $id and revision $revision"
            }
            "$id\t$revision\t$digest"
        }
        check(sha256(issueLines.joinToString("\n").toByteArray()) == catalog["digest"]) { "magazine catalog digest does not match its issues" }
        val extra = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }
            .filter { p -> p.split('/').none { it.startsWith('.') } && p !in listed }.toList()
        check(extra.isEmpty()) { "files in ${dir.path} not in catalog.json: ${extra.take(5)}" }
        out.writeText("${catalog["digest"]}\n")
    }
}
tasks.named("preBuild") { dependsOn(verifyMagazineAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc) // runs Search's SQL against a real SQLite in plain JUnit
}
