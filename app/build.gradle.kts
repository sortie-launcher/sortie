import java.nio.file.Files
import java.nio.file.LinkOption
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
        7
    } else {
        raw.takeIf { Regex("[1-9][0-9]{0,9}").matches(it) }?.toLong()?.takeIf { it <= 2_100_000_000L }?.toInt()
            ?: throw GradleException("sortieVersionCode must be a whole number from 1 to 2100000000, got \"$raw\"")
    }
}
val sortieVersionName: String = providers.gradleProperty("sortieVersionName").orNull.let { raw ->
    val semver = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")
    if (raw == null) {
        "0.1.0-beta.6"
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

// Magazines (Sortie Field Notes) are content kept in a separate repository, never in this one, and are optional: a build
// includes them only with -PsortieMagazinesDir=/path/to/sortie-zines/issues (a relative path resolves against the repository
// root), the folder tools/publish-magazines.py --out wrote: catalog.json plus one folder per issue. Its catalog digest must
// equal the one pinned in app/magazines.lock; every catalogued file must match its size and SHA-256 and the issue and catalog
// digests must add up; symlinks, uncatalogued visible files and magazines inside app sources are refused. Exactly the
// catalogued files are then staged as generated assets (assets/magazines/ in the APK). Without the property the staged folder
// is emptied on every build, so magazines from an earlier build in the same checkout can never leak into a build without
// them. Nothing here touches the network.
abstract class StageMagazineAssets : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val sourcePath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inTreeMagazines: ConfigurableFileCollection

    @get:Internal
    abstract val appDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        inTreeMagazines.files.firstOrNull { it.exists() }?.let {
            throw GradleException(
                "$it: magazines are no longer bundled from the app sources. Delete that folder and build with " +
                    "-PsortieMagazinesDir=/path/to/sortie-zines/issues to include them"
            )
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        if (!out.mkdirs()) throw GradleException("cannot create $out")
        val source = sourcePath.orNull ?: return
        val staged = temporaryDir.resolve("magazines")
        staged.deleteRecursively()
        verifyAndCopy(File(source), pinnedDigest(lockFile.get().asFile), staged)
        Files.move(staged.toPath(), out.resolve("magazines").toPath())
    }

    private fun pinnedDigest(lock: File): String {
        val lines = lock.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        return lines.singleOrNull()?.takeIf(HEX64::matches)
            ?: throw GradleException("$lock must hold exactly one catalog digest (64 lowercase hex characters) besides # comments")
    }

    /** Verifies [given] against [pinned], copying each catalogued file into [staged] as it is checked. */
    private fun verifyAndCopy(given: File, pinned: String, staged: File) {
        fun fail(message: String): Nothing = throw GradleException("-PsortieMagazinesDir=$given: $message")
        if (!given.isDirectory) fail("not a folder; point it at the issues folder of a sortie-zines checkout (catalog.json and one folder per issue)")
        val dir = given.toPath().toRealPath().toFile()
        val app = appDir.get().asFile.toPath().toRealPath().toFile()
        if (dir.startsWith(app)) fail("lies inside the app module ($app); keep magazine content in its own repository")

        val root = dir.toPath()
        val visible = Files.walk(root).use { entries -> entries.iterator().asSequence().filter { it != root }.map(root::relativize).toList() }
            .filter { rel -> rel.none { it.toString().startsWith(".") } } // dot-files are never published or staged
        visible.firstOrNull { Files.isSymbolicLink(root.resolve(it)) }?.let { fail("$it is a symbolic link; magazine content must be plain files and folders") }
        val visibleFiles = visible.filter { Files.isRegularFile(root.resolve(it), LinkOption.NOFOLLOW_LINKS) }.map { it.joinToString("/") }.toSet()

        if ("catalog.json" !in visibleFiles) fail("has no catalog.json; publish it with tools/publish-magazines.py")
        val catalogBytes = File(dir, "catalog.json").readBytes()
        val catalog = parseObject(catalogBytes) ?: fail("catalog.json is not a JSON object")
        if (catalog["format"] != 1) fail("catalog.json has unknown format ${catalog["format"]}")
        val digest = catalog["digest"]
        if (digest != pinned) {
            fail("catalog digest $digest is not the one pinned in app/magazines.lock ($pinned); use the pinned content, or review it and re-pin")
        }
        val ids = HashSet<String>()
        val listed = mutableSetOf("catalog.json")
        val issueLines = (catalog["issues"] as? List<*> ?: fail("catalog.json has no issue list")).map { raw ->
            val issue = raw as? Map<*, *> ?: fail("catalog.json has a malformed issue entry")
            val id = (issue["id"] as? String)?.takeIf { SLUG.matches(it) && ids.add(it) } ?: fail("catalog.json has a bad or repeated issue id ${issue["id"]}")
            val revision = (issue["revision"] as? Int)?.takeIf { it > 0 } ?: fail("$id: revision must be a positive whole number")
            val paths = HashSet<String>()
            val fileLines = (issue["files"] as? List<*> ?: fail("$id: no file list")).map { f ->
                val entry = f as? Map<*, *> ?: fail("$id: malformed file entry")
                val path = (entry["path"] as? String)?.takeIf { isSafePath(it) && paths.add(it) } ?: fail("$id: unsafe or repeated path ${entry["path"]}")
                val rel = "$id/$path"
                val size = (entry["size"] as? Number)?.toLong()?.takeIf { it >= 0 } ?: fail("$rel: bad size in catalog.json")
                val sha = (entry["sha256"] as? String)?.takeIf(HEX64::matches) ?: fail("$rel: bad sha256 in catalog.json")
                if (rel !in visibleFiles) fail("$rel is catalogued but missing or not a plain file")
                val bytes = File(dir, rel).readBytes()
                if (bytes.size.toLong() != size || sha256(bytes) != sha) fail("$rel differs from catalog.json; the checkout is damaged or was edited by hand")
                staged.resolve(rel).apply { parentFile.mkdirs() }.writeBytes(bytes)
                listed += rel
                "$path\t$size\t$sha"
            }
            if ("issue.json" !in paths) fail("$id: catalog.json lists no issue.json")
            val issueDigest = sha256(fileLines.joinToString("\n").toByteArray())
            if (issueDigest != issue["digest"]) fail("$id: issue digest does not match its files")
            val json = parseObject(File(dir, "$id/issue.json").readBytes())
            if (json == null || json["schemaVersion"] != 2 || json["id"] != id || json["revision"] != revision) {
                fail("$id/issue.json must be schemaVersion 2 with id $id and revision $revision")
            }
            "$id\t$revision\t$issueDigest"
        }
        if (ids.isEmpty()) fail("catalog.json lists no issues")
        if (sha256(issueLines.joinToString("\n").toByteArray()) != digest) fail("catalog digest does not match its issues")
        val extra = (visibleFiles - listed).sorted()
        if (extra.isNotEmpty()) fail("files not in catalog.json: ${extra.take(5)}")
        staged.resolve("catalog.json").writeBytes(catalogBytes)
    }

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
        val SLUG = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** The app's rule for paths inside an issue (Magazine.kt isSafeIssuePath), and never a hidden segment. */
        fun isSafePath(path: String): Boolean {
            if (path.isEmpty() || path.length > 240 || path.startsWith('/') || path.startsWith('~')) return false
            if (path.any { it == '\\' || it == ':' || it < ' ' || it == '\u007f' }) return false
            return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith('.') }
        }

        fun parseObject(bytes: ByteArray): Map<*, *>? = try {
            groovy.json.JsonSlurper().parseText(bytes.decodeToString().removePrefix("\uFEFF")) as? Map<*, *>
        } catch (e: Exception) {
            null
        }
    }
}

/** Hands the staged magazines folder to unit tests (BundledMagazineAssetsTest) as the system property sortie.stagedMagazines. */
class StagedMagazinesArgument(
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val staged: Provider<Directory>
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dsortie.stagedMagazines=${staged.get().asFile.resolve("magazines").absolutePath}")
}

val sortieMagazinesDir: String? = providers.gradleProperty("sortieMagazinesDir").orNull?.let { raw ->
    if (raw.isBlank()) throw GradleException("sortieMagazinesDir is empty; omit it to build without magazines")
    rootDir.resolve(raw).absolutePath
}
val stageMagazineAssets = tasks.register<StageMagazineAssets>("stageMagazineAssets") {
    description = "Stages verified magazines from -PsortieMagazinesDir as generated assets, or none without it"
    sortieMagazinesDir?.let {
        sourcePath.set(it)
        sourceFiles.from(it)
    }
    lockFile.set(layout.projectDirectory.file("magazines.lock"))
    inTreeMagazines.from(listOf("main", "debug", "release").map { "src/$it/assets/magazines" })
    appDir.set(layout.projectDirectory)
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(stageMagazineAssets, StageMagazineAssets::outputDir) }
}
tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(StagedMagazinesArgument(stageMagazineAssets.flatMap { it.outputDir }))
}

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
