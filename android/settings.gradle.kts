// Optional mirror for Google's Maven repository, for networks that cannot reach dl.google.com: set
// tether.googleMavenMirror=<url> in local.properties (git-ignored) or TETHER_GOOGLE_MAVEN_MIRROR in the environment.
val googleMirror: String? = run {
    val f = file("local.properties")
    val p = java.util.Properties().apply { if (f.exists()) f.inputStream().use { load(it) } }
    (p.getProperty("tether.googleMavenMirror") ?: System.getenv("TETHER_GOOGLE_MAVEN_MIRROR"))?.takeIf { it.isNotBlank() }
}

pluginManagement {
    repositories {
        val f = file("local.properties")
        val p = java.util.Properties().apply { if (f.exists()) f.inputStream().use { load(it) } }
        (p.getProperty("tether.googleMavenMirror") ?: System.getenv("TETHER_GOOGLE_MAVEN_MIRROR"))?.takeIf { it.isNotBlank() }?.let { maven(it) }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        googleMirror?.let { maven(it) }
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") }
        }
    }
}
rootProject.name = "Tether"
include(":app", ":helper", ":core", ":client")
