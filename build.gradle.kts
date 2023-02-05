import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val projectArend = gradle.includedBuild("Arend")
group = "org.ice1000.arend.lsp"
version = "0.4.0"

plugins {
  idea
  kotlin("jvm") version "1.8.10"
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.arend:base")
  implementation("org.arend:cli")
  implementation("org.arend:parser")
  val lsp4jVersion = "0.19.0"
  // Don't forget to keep it up-to-date with Arend
  val cliVersion = "1.4"
  val antlrVersion = "4.8"
  implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4jVersion")
  implementation(group = "commons-cli", name = "commons-cli", version = cliVersion)
  implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:$lsp4jVersion")
  implementation("org.antlr:antlr4-runtime:$antlrVersion")
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions {
    jvmTarget = "17"
    languageVersion = "1.8"
    apiVersion = "1.8"
    useK2 = true
    freeCompilerArgs = listOf("-Xjvm-default=all")
  }
}

idea {
  module {
    outputDir = buildDir.resolve("classes/java/intellij")
    testOutputDir = buildDir.resolve("classes/java/testIntellij")
  }
}

val jarDep = tasks.register<Jar>("jarDep") {
  group = "build"
  dependsOn(projectArend.task(":cli:jar"), projectArend.task(":base:jar"), tasks.jar)
  manifest.attributes["Main-Class"] = "${project.group}.ServerKt"
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it as Any else zipTree(it) }) {
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
  }
  from(sourceSets["main"].output)
  archiveClassifier.set("full")
}

val copyJarDep = tasks.register<Copy>("copyJarDep") {
  dependsOn(jarDep)
  from(jarDep.get().archiveFile.get().asFile)
  into(rootProject.buildDir.resolve("server"))
  rename { "lsp.jar" }
  outputs.upToDateWhen { false }
}

tasks.withType<Wrapper> {
  gradleVersion = "8.0-rc-2"
}
