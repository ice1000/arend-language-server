import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val projectArend = gradle.includedBuild("Arend")
group = "org.ice1000.arend.lsp"
version = "0.1.0"

plugins {
  idea
  kotlin("jvm") version "1.3.72"
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.arend:base")
  implementation("org.arend:cli")
  implementation(kotlin("stdlib-jdk8"))
  val lsp4jVersion = "0.9.0"
  implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4jVersion")
  // Don't forget to keep it up-to-date with Arend
  val cliVersion = "1.4"
  implementation(group = "commons-cli", name = "commons-cli", version = cliVersion)
  implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:$lsp4jVersion")
}

configure<JavaPluginConvention> {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    jvmTarget = "11"
    languageVersion = "1.3"
    apiVersion = "1.3"
    freeCompilerArgs = listOf("-Xjvm-default=enable")
  }
}

idea {
  module {
    outputDir = buildDir.resolve("classes/java/intellij")
    testOutputDir = buildDir.resolve("classes/java/testIntellij")
  }
}

val jarDep = task<Jar>("jarDep") {
  group = "build"
  manifest.attributes["Main-Class"] = "${project.group}.ServerKt"
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
  from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it as Any else zipTree(it) }) {
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
  }
  from(sourceSets["main"].output)
  archiveClassifier.set("full")
}

val copyJarDep = task<Copy>("copyJarDep") {
  dependsOn(jarDep)
  from(jarDep.archiveFile.get().asFile)
  into(System.getProperty("user.dir"))
  outputs.upToDateWhen { false }
}

tasks.withType<Wrapper> {
  gradleVersion = "6.5"
}
