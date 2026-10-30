// LootBot ZenithProxy plugin. Its own Gradle build: ZenithProxy's plugin toolchain lives on
// maven.2b2t.vc and needs JDK 25 (fetched by the Foojay resolver when missing).
plugins {
    id("zenithproxy.plugin.dev") version "1.2.+"
}

group = property("maven_group") as String
version = property("plugin_version") as String
val mc = property("mc") as String
val pluginId = property("plugin_id") as String

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

zenithProxyPlugin {
    // Generates dev.lootbot.BuildConstants
    buildConstants {
        fields = mapOf(
            "VERSION" to project.version.toString(),
            "MC_VERSION" to mc,
            "PLUGIN_ID" to pluginId,
            "MAVEN_GROUP" to project.group.toString(),
        )
    }
    // ZenithProxy itself runs on 21+
    javaReleaseVersion = JavaLanguageVersion.of(21)
    runTaskMixinLauncher = true
}

repositories {
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
    mavenCentral()
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$mc-SNAPSHOT")
    // Bundled and relocated so it never clashes with another plugin's copy.
    shade("com.google.code.gson:gson:2.11.0")
}

tasks {
    shadowJar {
        relocate("com.google.gson", "${project.group}.shadow.gson")
        dependencies {
            exclude(dependency("org.slf4j:.*"))
        }
    }
}
