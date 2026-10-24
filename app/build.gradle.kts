import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Version: the defaults are the current preview. -PsortieVersionCode=N and -PsortieVersionName=X.Y.Z[-pre] override them,
// validated, for upgrade smokes and later releases (tools/release.sh passes its arguments through).
val sortieVersionCode: Int = providers.gradleProperty("sortieVersionCode").orNull.let { raw ->
    if (raw == null) {
        5
    } else {
        raw.takeIf { Regex("[1-9][0-9]{0,9}").matches(it) }?.toLong()?.takeIf { it <= 2_100_000_000L }?.toInt()
            ?: throw GradleException("sortieVersionCode must be a whole number from 1 to 2100000000, got \"$raw\"")
    }
}
val sortieVersionName: String = providers.gradleProperty("sortieVersionName").orNull.let { raw ->
    val semver = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")
    if (raw == null) {
        "0.1.0-beta.4"
    } else {
        raw.takeIf { it.length <= 64 && semver.matches(it) }
            ?: throw GradleException("sortieVersionName must look like 1.2.3 or 1.2.3-beta.4 (at most 64 characters), got \"$raw\"")
    }
}

// Release signing comes only from the environment, all or nothing: SORTIE_RELEASE_KEYSTORE (absolute path to a PKCS12 store),
// SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE (absolute path to a file holding the store password, which is also the key password)
// and SORTIE_RELEASE_KEY_ALIAS. None set: assembleRelease stays unsigned (CI). Some set: the build fails; there is never a
// fallback to the debug key. The password is read from a file so it is never in an argument list, and signing refuses the
// configuration cache so it is never persisted there. tools/init-release-key.sh creates the key; tools/release.sh uses it.
class ReleaseSigning(val storeFile: File, val password: String, val keyAlias: String)

val releaseSigning: ReleaseSigning? = run {
    val names = listOf("SORTIE_RELEASE_KEYSTORE", "SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE", "SORTIE_RELEASE_KEY_ALIAS")
    val values = names.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
    if (values.values.all { it == null }) return@run null
    val missing = values.filterValues { it == null }.keys
    if (missing.isNotEmpty()) {
        throw GradleException(
            "Release signing is only partly configured: set all of ${names.joinToString()} or none. Missing: ${missing.joinToString()}"
        )
    }
    if (gradle.startParameter.isConfigurationCacheRequested) {
        throw GradleException("Release signing reads a password; run Gradle with --no-configuration-cache so it is never stored in the configuration cache")
    }
    val projectRoot = rootDir.canonicalFile.toPath()
    fun signingFile(variable: String): File {
        val file = File(values.getValue(variable)!!)
        if (!file.isAbsolute) throw GradleException("$variable must be an absolute path")
        if (!file.isFile || !file.canRead()) throw GradleException("$variable: $file is not a readable file")
        if (file.canonicalFile.toPath().startsWith(projectRoot)) {
            throw GradleException("$variable: $file is inside the project; keep signing files out of the repository")
        }
        return file
    }
    val storeFile = signingFile("SORTIE_RELEASE_KEYSTORE")
    val passwordFile = signingFile("SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE")
    val alias = values.getValue("SORTIE_RELEASE_KEY_ALIAS")!!
    val password = passwordFile.readText().removeSuffix("\n").removeSuffix("\r")
    if (password.length < 6 || password.any { it == '\n' || it == '\r' }) {
        throw GradleException("$passwordFile must hold a single line password of at least 6 characters")
    }
    val keyStore = try {
        KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password.toCharArray()) } }
    } catch (e: Exception) {
        throw GradleException("Cannot open $storeFile as a PKCS12 keystore with the password in $passwordFile (${e.javaClass.simpleName})")
    }
    val certificate = keyStore.takeIf { it.isKeyEntry(alias) }?.getCertificate(alias) as? X509Certificate
        ?: throw GradleException("$storeFile has no private key entry with alias \"$alias\"")
    try {
        keyStore.getKey(alias, password.toCharArray())
    } catch (e: Exception) {
        throw GradleException("The key \"$alias\" in $storeFile does not open with the store password (${e.javaClass.simpleName})")
    }
    if (certificate.subjectX500Principal.name.contains("CN=Android Debug")) {
        throw GradleException("$storeFile holds an Android debug certificate; release builds must use the release key")
    }
    ReleaseSigning(storeFile, password, alias)
}

android {
    namespace = "io.github.esko997.sortie"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.esko997.sortie"
        minSdk = 30 // MANAGE_EXTERNAL_STORAGE exists from API 30
        targetSdk = 35
        versionCode = sortieVersionCode
        versionName = sortieVersionName
    }
    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.password
                keyAlias = signing.keyAlias
                keyPassword = signing.password
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned (app-release-unsigned.apk) unless the complete release signing environment above is present.
            signingConfig = signingConfigs.findByName("release")
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
