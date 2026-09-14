plugins {
    java
    jacoco
    checkstyle
    id("com.gradleup.shadow") version "9.4.0"
    id("com.diffplug.spotless") version "8.5.1"
}

group = "com.wellsetups"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/") {
        content { includeGroup("com.github.retrooper") }
    }
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        content { includeGroup("org.spigotmc") }
    }
    maven("https://repo.extendedclip.com/releases/") {
        content { includeGroup("me.clip") }
    }
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.MilkBowl")
            includeGroup("com.github.Gypopo")
            includeGroup("com.github.brcdev-minecraft")
        }
    }
}

val compatibility by sourceSets.creating
val modernApi by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    add(compatibility.implementationConfigurationName, "org.ow2.asm:asm:9.8")
    modernApi("org.spigotmc:spigot-api:26.2-R0.1-20260816.205300-12")
    compileOnly("org.spigotmc:spigot-api:1.20-R0.1-20230612.113428-32")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.11.6") { isTransitive = false }
    compileOnly("com.github.Gypopo:EconomyShopGUI-API:1.10.1") { isTransitive = false }
    compileOnly("com.github.brcdev-minecraft:shopgui-api:3.2.0") { isTransitive = false }
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0") { isTransitive = false }
    testImplementation("com.github.retrooper:packetevents-spigot:2.13.0") { isTransitive = false }
    compileOnly("com.github.retrooper:packetevents-api:2.13.0") { isTransitive = false }
    testImplementation("com.github.retrooper:packetevents-api:2.13.0") { isTransitive = false }
    testImplementation("com.github.retrooper:packetevents-netty-common:2.13.0") { isTransitive = false }
    testRuntimeOnly("io.netty:netty-buffer:4.1.138.Final")
    testRuntimeOnly("io.netty:netty-transport:4.1.138.Final")
    testRuntimeOnly("net.kyori:adventure-nbt:4.26.1")
    testRuntimeOnly("net.kyori:adventure-api:4.26.1")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")
    implementation("org.yaml:snakeyaml:2.3")
    // The server's plugin.yml library loader caches JDBC separately from the plugin JAR.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    constraints {
        add("checkstyle", "commons-beanutils:commons-beanutils:1.11.0") {
            because("Fix CVE-2025-48734 in Checkstyle's build-time dependency")
        }
    }
    implementation("org.bstats:bstats-bukkit:3.2.1")
    testImplementation("org.spigotmc:spigot-api:1.20-R0.1-20230612.113428-32")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val checkApiCompatibility by tasks.registering(JavaExec::class) {
    dependsOn(tasks.classes, compatibility.classesTaskName)
    classpath = compatibility.runtimeClasspath
    mainClass.set("com.wellsetups.tools.ApiCompatibility")
    doFirst {
        args = listOf(layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath,
            modernApi.singleFile.absolutePath)
    }
}
tasks.check { dependsOn(checkApiCompatibility) }

java { withSourcesJar() }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.processResources {
    val releaseVersion = project.version.toString()
    filteringCharset = "UTF-8"
    inputs.property("version", releaseVersion)
    filesMatching("plugin.yml") { expand("version" to releaseVersion) }
    from(listOf("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF/wellsell") }
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.jar { archiveClassifier.set("thin") }
tasks.shadowJar {
    archiveClassifier.set("")
    relocate("net.kyori", "com.wellsetups.WellSell.lib.kyori")
    relocate("org.yaml.snakeyaml", "com.wellsetups.WellSell.lib.yaml")
    relocate("org.bstats", "com.wellsetups.WellSell.lib.bstats")
    // JDBC drivers retain their resource/native library paths and are loaded explicitly.
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.assemble { dependsOn(tasks.shadowJar) }
tasks.test {
    useJUnitPlatform()
    if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_24)) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
    finalizedBy(tasks.jacocoTestReport)
}
val testPackagedLore by tasks.registering(Test::class) {
    description = "Verify packet component round trips against the shaded release JAR"
    dependsOn(tasks.shadowJar, tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = files(tasks.shadowJar.flatMap { it.archiveFile }) +
        (sourceSets.test.get().runtimeClasspath - sourceSets.main.get().output)
    useJUnitPlatform()
    filter { includeTestsMatching("com.wellsetups.WellSell.lore.LoreMarkerTest") }
}
tasks.check { dependsOn(testPackagedLore) }
jacoco { toolVersion = "0.8.14" }
tasks.jacocoTestReport { reports { xml.required.set(true); html.required.set(true) } }
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf("com.wellsetups.WellSell.sell.TransactionCore")
            limit { counter = "BRANCH"; minimum = "0.90".toBigDecimal() }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
checkstyle { toolVersion = "10.21.4"; configFile = file("config/checkstyle/checkstyle.xml") }
spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java { googleJavaFormat("1.28.0"); removeUnusedImports(); trimTrailingWhitespace(); endWithNewline() }
}
dependencyLocking {
    lockAllConfigurations()
    // Gradle normalizes timestamped snapshots when writing locks, creating an
    // unsatisfiable constraint on the next run. These APIs are pinned explicitly
    // above and still SHA-256 verified; all other dependencies remain locked.
    ignoredDependencies.add("org.spigotmc:spigot-api")
}
tasks.wrapper { gradleVersion = "9.5.1"; distributionType = Wrapper.DistributionType.BIN }
