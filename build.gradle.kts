plugins {
    id("java-library")
}

repositories {
    // PaperMC en premier : Maven Central limite les téléchargements (429)
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
    // EterLib et VaultAPI : compilés depuis GitHub
    maven("https://jitpack.io")
    // Repli : EterLib publié sur cette machine (`gradlew publishToMavenLocal` dans EterLib), pour tester avant de pousser
    mavenLocal()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    // Socle commun : base, langues, menus, serveurs du réseau (plugin EterLib installé sur le serveur)
    compileOnly("com.github.Eternom:EterLib:1.7.0")
    // Achat de temps : Vault (fourni par EterEconomy)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        // Déclarée comme entrée : sinon le cache de Gradle réutilise un plugin.yml avec l'ancienne version
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

// Après chaque build, copie le jar dans <eterPluginsDir>/paper et y supprime l'ancienne version de ce plugin.
// eterPluginsDir se règle dans ~/.gradle/gradle.properties (propre à ta machine) : sans lui, rien n'est copié (JitPack...).
val deployPlugin by tasks.registering(Copy::class) {
    description = "Copie le jar dans le dossier de plugins local (eterPluginsDir)"
    // Variables locales à la tâche : le cache de configuration de Gradle refuse les variables du script
    val eterPluginsDir = providers.gradleProperty("eterPluginsDir")
    val enabled = eterPluginsDir.isPresent
    onlyIf { enabled }
    val jarName = tasks.jar.flatMap { it.archiveBaseName }
    from(tasks.jar)
    into(eterPluginsDir.map { "$it/paper" }.orElse(layout.buildDirectory.dir("deploy").map { it.asFile.path }))
    doFirst {
        destinationDir.listFiles { file -> file.name.startsWith(jarName.get() + "-") && file.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }
}
tasks.build { finalizedBy(deployPlugin) }
