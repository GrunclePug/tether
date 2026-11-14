plugins {
    // Loom 1.18+ needs the Gradle daemon on Java 25; 1.17.x is the newest line that runs on 21.
    id("fabric-loom") version "1.17.21"
}

version = property("mod.version").toString()
group = property("mod.group").toString()
base.archivesName = property("mod.id").toString()

repositories {
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
    // Baritone publishes its API jar only as a GitHub release asset.
    ivy("https://github.com/cabaletta/baritone/releases/download/") {
        name = "Baritone releases"
        patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeModule("cabaletta", "baritone-api-fabric") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("deps.minecraft")}")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    // Optional integrations: compiled against, never bundled, only touched when the mod is loaded.
    modCompileOnly("cabaletta:baritone-api-fabric:${property("deps.baritone")}")
    modCompileOnly("maven.modrinth:XZNI4Cpy:${property("deps.toms_storage")}")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

loom {
    runs {
        // Separate directories so a dev client and a dev server can run side by side for testing.
        named("client") { runDir("run/client") }
        named("server") { runDir("run/server") }
    }
}
