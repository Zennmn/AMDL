pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "AMDL-Windows"

// Compile the supplied Miuix checkout, including its desktop window implementation.
includeBuild("../miuix") {
    dependencySubstitution {
        listOf("miuix-ui", "miuix-preference", "miuix-icons", "miuix-window").forEach { name ->
            substitute(module("top.yukonga.miuix.kmp:$name")).using(project(":$name"))
        }
    }
}
