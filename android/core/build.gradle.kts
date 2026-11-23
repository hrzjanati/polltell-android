// Protocol and codec code shared by the Android phone's app (:app) and the Android client (:client):
// framing, handshake, sealed records, message types, G.711 and Opus.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tether.core"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets["test"].resources.srcDir("../../protocol") // fixtures/ and PROTOCOL.md for the contract tests
}

kotlin { jvmToolchain(17) }

dependencies {
    api("io.github.jaredmdobson:concentus:1.0.2") // pure-Java libopus port: synchronous, full encoder control
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
