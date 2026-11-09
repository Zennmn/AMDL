import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "com.kyant.amdl"
version = "1.1.6"
val miuixVersion = "0.9.4"

require(System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")) {
    "This distribution requires an x64 JDK because its native engine targets Windows x64."
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-icons:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-window:$miuixVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    providers.systemProperty("amdl.browser.smoke").orNull?.let {
        systemProperty("amdl.browser.smoke", it)
    }
    providers.systemProperty("amdl.ui.render").orNull?.let {
        systemProperty("amdl.ui.render", it)
    }
}

compose.desktop {
    application {
        mainClass = "com.kyant.amdl.desktop.MainKt"
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs("-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            outputBaseDir.set(project.layout.buildDirectory.dir("compose/binaries/windows"))
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "AMDL"
            packageVersion = project.version.toString()
            description = "AMDL - Apple Music Downloader for Windows"
            vendor = "AMDL contributors"
            licenseFile.set(project.file("LICENSE"))
            modules("java.desktop", "java.net.http", "java.xml", "java.logging", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                iconFile.set(project.file("assets/amdl.ico"))
                menuGroup = "AMDL"
                shortcut = true
                dirChooser = true
                upgradeUuid = "0977af59-c886-4394-a83a-15ebc311ae80"
            }
        }
    }
}
