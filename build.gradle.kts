import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.kotlin.dsl.getByType

plugins {
    id("idea")
    id("fabric-loom") version "1.17-SNAPSHOT" apply false
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT" apply false
    id("net.neoforged.moddev") version "2.0.147" apply false
    id("me.modmuss50.mod-publish-plugin") version "1.1.0"
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
}

val modVersion: String = providers.gradleProperty("mod_version").get()
val mavenGroup: String = providers.gradleProperty("maven_group").get()
val modId: String = providers.gradleProperty("mod_id").get()

version = modVersion
group = mavenGroup

val isFabric = project.name.endsWith("-fabric")
val isNeoForge = project.name.endsWith("-neoforge")
val mcVersion = stonecutter.current.version

val loader = when {
    isFabric -> "fabric"
    isNeoForge -> "neoforge"
    else -> error("Unknown loader for project ${project.name}")
}

println("[${project.rootDir}] ${project.version} - Minecraft Version is $mcVersion ($loader)")

stonecutter {
    constants.match(loader, "fabric", "neoforge")
}

base {
    archivesName.set("${rootProject.name}-$mcVersion-$loader")
}

sourceSets {
    main {
        if (isFabric) {
            resources.srcDir(rootProject.file("src/fabric/resources"))
        } else if (isNeoForge) {
            resources.srcDir(rootProject.file("src/neoforge/resources"))
        }
    }
}

if (isFabric) {
    // 1.21.11 is the last obfuscated version and still needs the remapping Loom plugin.
    apply(
        plugin = if (mcVersion == "1.21.11") {
            "fabric-loom"
        } else {
            "net.fabricmc.fabric-loom"
        }
    )
} else if (isNeoForge) {
    apply(plugin = "net.neoforged.moddev")
}

data class FabricVersionSet(
    val loader: String,
    val fabricApi: String,
    val fabricKotlin: String
)

val fabricVersions = when (mcVersion) {
    "1.21.11" -> FabricVersionSet(
        loader = "0.19.5",
        fabricApi = "0.141.6+1.21.11",
        fabricKotlin = "1.14.1+kotlin.2.4.20"
    )

    "26.1.2" -> FabricVersionSet(
        loader = "0.19.5",
        fabricApi = "0.155.3+26.1.2",
        fabricKotlin = "1.14.1+kotlin.2.4.20"
    )

    "26.2" -> FabricVersionSet(
        loader = "0.19.5",
        fabricApi = "0.160.0+26.2",
        fabricKotlin = "1.14.1+kotlin.2.4.20"
    )

    "26.3" -> FabricVersionSet(
        loader = "0.19.5",
        fabricApi = "0.160.5+26.3",
        fabricKotlin = "1.14.1+kotlin.2.4.20"
    )

    else -> error("No Fabric versions configured for $mcVersion")
}

data class NeoForgeVersionSet(
    val kotlinforforge: String,
    val neoforgeVersion: String,
    val neoforgeVersionRange: String,
    val minecraftVersionRange: String
)

val neoforgeVersions = when (mcVersion) {
    "1.21.11" -> NeoForgeVersionSet(
        kotlinforforge = "6.3.0",
        neoforgeVersion = "21.11.45",
        neoforgeVersionRange = "[21.11,)",
        minecraftVersionRange = "[1.21.11,)"
    )

    "26.1.2" -> NeoForgeVersionSet(
        kotlinforforge = "6.3.0",
        neoforgeVersion = "26.1.2.109",
        neoforgeVersionRange = "[26.1,)",
        minecraftVersionRange = "[26.1,)"
    )

    "26.2" -> NeoForgeVersionSet(
        kotlinforforge = "6.3.0",
        neoforgeVersion = "26.2.0.88",
        neoforgeVersionRange = "[26.2,)",
        minecraftVersionRange = "[26.2,)"
    )

    // KFF 6.3.0 declares minecraft [1.21.9,26.3), so this version goes through the
    // `patchKotlinForForge` workaround below - drop it from `kffNeedsPatch` once a
    // KFF release covers 26.3.
    "26.3" -> NeoForgeVersionSet(
        kotlinforforge = "6.3.0",
        neoforgeVersion = "26.3.0.23-beta",
        neoforgeVersionRange = "[26.3.0.23-beta,)",
        minecraftVersionRange = "[26.3,)"
    )

    else -> error("No NeoForge versions configured for $mcVersion")
}

// YACL publishes one artifact per Minecraft version and loader: `<yacl>+<mc>-<loader>`.
val yaclVersion = when (mcVersion) {
    "1.21.11" -> "3.8.2+1.21.11"
    "26.1.2" -> "3.9.7+26.1"
    "26.2" -> "3.9.7+26.2"
    "26.3" -> "3.9.7+26.3"
    else -> error("No YACL version configured for $mcVersion")
} + "-$loader"

// Mod Menu is Fabric-only and optional: compiled against, present in dev runs, not required at runtime.
val modMenuVersion = when (mcVersion) {
    "1.21.11" -> "17.0.1"
    "26.1.2" -> "18.0.2"
    "26.2" -> "20.0.3"
    "26.3" -> "21.0.0"
    else -> error("No Mod Menu version configured for $mcVersion")
}

repositories {
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
    }
    maven {
        name = "Xander Maven"
        url = uri("https://maven.isxander.dev/releases")
    }
    maven {
        // YACL's transitive dependencies (quilt parsers)
        name = "QuiltMC"
        url = uri("https://maven.quiltmc.org/repository/release/")
    }
    maven {
        name = "TerraformersMC"
        url = uri("https://maven.terraformersmc.com/releases/")
    }
    maven {
        // Mod Menu's transitive dependency (Text Placeholder API)
        name = "Nucleoid"
        url = uri("https://maven.nucleoid.xyz/")
    }
}

if (isFabric) {
    configure<LoomGradleExtensionAPI> {
        mods {
            create(modId) {
                sourceSet(sourceSets.main.get())
            }
        }
    }
} else if (isNeoForge) {
    configure<net.neoforged.moddevgradle.dsl.NeoForgeExtension> {
        version = neoforgeVersions.neoforgeVersion
        runs {
            create("client") {
                client()
            }
        }
        mods {
            create(modId) {
                sourceSet(sourceSets.main.get())
            }
        }
    }
}

// Kotlin for Forge caps its supported Minecraft range; for versions newer than the
// latest KFF release we repackage kffmod with the upper bound removed.
val kffNeedsPatch = isNeoForge && mcVersion == "26.3"

val patchedKff = configurations.create("patchedKff")

val unzipKff = tasks.register<Copy>("unzipKff") {
    val jarFile = provider { patchedKff.files.firstOrNull() }
    dependsOn(patchedKff)

    from(provider { jarFile.get()?.let { zipTree(it) } ?: files() })
    into(layout.buildDirectory.dir("tmp/unzipKff"))

    doLast {
        val tomlFile = layout.buildDirectory.file("tmp/unzipKff/META-INF/neoforge.mods.toml").get().asFile
        if (tomlFile.exists()) {
            var content = tomlFile.readText()
            content = content.replace(Regex("""versionRange="\[1\.21\.9,[^)\]]*\)""""), "versionRange=\"[1.21.9,)\"")
            tomlFile.writeText(content)
        }
    }
}

val patchKotlinForForge = tasks.register<Zip>("patchKotlinForForge") {
    dependsOn(unzipKff)
    from(layout.buildDirectory.dir("tmp/unzipKff"))
    archiveFileName.set("kffmod-neoforge-patched.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

dependencies {
    if (kffNeedsPatch) {
        "patchedKff"("thedarkcolour:kffmod-neoforge:${neoforgeVersions.kotlinforforge}")
    }
    if (isFabric) {
        add("minecraft", "com.mojang:minecraft:$mcVersion")

        if (mcVersion == "1.21.11") {
            add("mappings", project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())

            add("modImplementation", "net.fabricmc:fabric-loader:${fabricVersions.loader}")
            add("modImplementation", "net.fabricmc.fabric-api:fabric-api:${fabricVersions.fabricApi}")
            add("modImplementation", "net.fabricmc:fabric-language-kotlin:${fabricVersions.fabricKotlin}")
            add("modImplementation", "dev.isxander:yet-another-config-lib:$yaclVersion")
            add("modCompileOnly", "com.terraformersmc:modmenu:$modMenuVersion")
            add("modLocalRuntime", "com.terraformersmc:modmenu:$modMenuVersion")
        } else {
            add("implementation", "net.fabricmc:fabric-loader:${fabricVersions.loader}")
            add("implementation", "net.fabricmc.fabric-api:fabric-api:${fabricVersions.fabricApi}")
            add("implementation", "net.fabricmc:fabric-language-kotlin:${fabricVersions.fabricKotlin}")
            add("implementation", "dev.isxander:yet-another-config-lib:$yaclVersion")
            add("compileOnly", "com.terraformersmc:modmenu:$modMenuVersion")
            add("localRuntime", "com.terraformersmc:modmenu:$modMenuVersion")
        }
    } else if (isNeoForge) {
        "implementation"("dev.isxander:yet-another-config-lib:$yaclVersion")
        if (kffNeedsPatch) {
            val kff = dependencies.create("thedarkcolour:kotlinforforge-neoforge:${neoforgeVersions.kotlinforforge}") as ModuleDependency
            kff.exclude(mapOf("group" to "thedarkcolour", "module" to "kffmod-neoforge"))
            "implementation"(kff)
            "implementation"(files(patchKotlinForForge.map { it.archiveFile.get() }))
        } else {
            "implementation"("thedarkcolour:kotlinforforge-neoforge:${neoforgeVersions.kotlinforforge}")
        }
    }
}

val minecraftDependency = when {
    mcVersion == "1.21.11" -> ">=1.21.11 <1.22"
    mcVersion.startsWith("26.1") -> ">=26.1 <26.2"
    mcVersion.startsWith("26.2") -> ">=26.2 <26.3"
    mcVersion.startsWith("26.3") -> ">=26.3 <26.4"
    else -> error("No minecraft dependency range configured for $mcVersion")
}

tasks.withType<ProcessResources>().configureEach {
    inputs.property("version", project.version)
    inputs.property("minecraft_dependency", minecraftDependency)

    if (isFabric) {
        inputs.property("loader_version", fabricVersions.loader)
        filesMatching("fabric.mod.json") {
            expand(
                "version" to project.version,
                "minecraft_dependency" to minecraftDependency,
                "loader_version" to fabricVersions.loader
            )
        }
    } else if (isNeoForge) {
        // NeoForge 26.3's mod list shows a square `iconFile`; older versions only know `logoFile`.
        val logoProperty = if (stonecutter.eval(mcVersion, ">=26.3")) "iconFile" else "logoFile"
        inputs.property("logo_property", logoProperty)
        inputs.property("neoforge_version_range", neoforgeVersions.neoforgeVersionRange)
        inputs.property("minecraft_version_range", neoforgeVersions.minecraftVersionRange)
        inputs.property("kotlinforforge_version", neoforgeVersions.kotlinforforge)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(
                "version" to project.version,
                "neoforge_version_range" to neoforgeVersions.neoforgeVersionRange,
                "minecraft_version_range" to neoforgeVersions.minecraftVersionRange,
                "kotlinforforge_version" to neoforgeVersions.kotlinforforge,
                "logo_property" to logoProperty
            )
        }
    }
}

val javaVersion = when {
    mcVersion == "1.21.11" -> JavaVersion.VERSION_21
    mcVersion.startsWith("26.") -> JavaVersion.VERSION_25
    else -> error("No Java version configured for $mcVersion")
}

val kotlinJvmTarget = when {
    mcVersion == "1.21.11" -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    mcVersion.startsWith("26.") -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    else -> error("No Kotlin JVM target configured for $mcVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(
        when {
            mcVersion == "1.21.11" -> 21
            mcVersion.startsWith("26.") -> 25
            else -> error("No Java release configured for $mcVersion")
        }
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(kotlinJvmTarget)
    }
}

java {
    withSourcesJar()
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

tasks.named<Jar>("jar").configure {
    inputs.property("projectName", project.name)
    from(rootProject.file("LICENSE")) {
        rename { "${it}_${project.name}" }
    }
}

publishMods {
    // `DRY_RUN=1 ./gradlew publishMods` builds and validates everything without uploading
    dryRun.set(providers.environmentVariable("DRY_RUN").isPresent)

    if (isFabric) {
        if (mcVersion == "1.21.11") {
            file.set(tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar").get().archiveFile)
        } else {
            file.set(tasks.named<Jar>("jar").get().archiveFile)
        }
        modLoaders.add("fabric")
    } else if (isNeoForge) {
        file.set(tasks.named<Jar>("jar").get().archiveFile)
        modLoaders.add("neoforge")
    }

    val changelogFile = rootProject.file("CHANGELOG.md")
    if (changelogFile.exists()) {
        changelog.set(changelogFile.readText())
    } else {
        changelog.set("No changelog provided for this release.")
    }

    // RELEASE_TYPE (stable, beta, alpha) is set by the release workflow
    type.set(
        when (providers.environmentVariable("RELEASE_TYPE").getOrElse("stable").lowercase()) {
            "beta" -> me.modmuss50.mpp.ReleaseType.BETA
            "alpha" -> me.modmuss50.mpp.ReleaseType.ALPHA
            else -> me.modmuss50.mpp.ReleaseType.STABLE
        }
    )
    displayName.set("mc$mcVersion-$modVersion-$loader")

    // Project ids are set once the Modrinth / CurseForge projects exist (`modrinth_id` / `curseforge_id`).
    val modrinthToken = providers.environmentVariable("MODRINTH_TOKEN")
    val modrinthId = providers.gradleProperty("modrinth_id")
    if (modrinthToken.isPresent && modrinthId.isPresent) {
        modrinth {
            accessToken.set(modrinthToken)
            projectId.set(modrinthId)
            minecraftVersions.add(mcVersion)

            requires("yacl")
            if (isFabric) {
                requires("fabric-api", "fabric-language-kotlin")
                optional("modmenu")
            } else if (isNeoForge) {
                requires("kotlin-for-forge")
            }
        }
    }

    val curseforgeToken = providers.environmentVariable("CURSEFORGE_TOKEN")
    val curseforgeId = providers.gradleProperty("curseforge_id")
    if (curseforgeToken.isPresent && curseforgeId.isPresent) {
        curseforge {
            accessToken.set(curseforgeToken)
            projectId.set(curseforgeId)
            projectSlug.set(modId)
            minecraftVersions.add(mcVersion)
            javaVersions.add(javaVersion)
            clientRequired.set(true)
            serverRequired.set(false)

            requires("yacl")
            if (isFabric) {
                requires("fabric-api", "fabric-language-kotlin")
                optional("modmenu")
            } else if (isNeoForge) {
                requires("kotlin-for-forge")
            }
        }
    }
}
