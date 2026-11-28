plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    `maven-publish`
}

group = "com.aayan"
version = "1.3.0"

val paperVersion = "26.3.build.252-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://jitpack.io") {
        content { includeGroup("com.github.MilkBowl") }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")

    // Shaded + relocated. Paper 26.3 already ships the MySQL and SQLite JDBC drivers and SLF4J.
    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j")
    }

    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Same driver versions that Paper 26.3 build 252 bundles.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.2.0")
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(props) }
    }

    jar {
        // The plain jar is the API artifact for child plugins; the shaded jar is the server plugin.
        archiveClassifier.set("api")
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("com.zaxxer.hikari", "com.aayan.albcore.libs.hikari")
        // Kotlin is NOT relocated on purpose: child plugins (ALBLevel, ALBSkills...) use
        // ALBCore's Kotlin stdlib, and ALBCore's public API exposes Kotlin function types.
        exclude("META-INF/maven/**", "org/intellij/lang/annotations/**", "org/jetbrains/annotations/**")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "alb-core"
            artifact(tasks.jar) { classifier = null }
            artifact(tasks.named("sourcesJar"))
            pom {
                name.set("ALBCore")
                description.set("Base framework for the ALB plugins (GUI, commands, database, player data).")
            }
        }
    }
}
