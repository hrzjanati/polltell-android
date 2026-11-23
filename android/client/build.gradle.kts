import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release key from local.properties (git-ignored); without one, release builds use the debug key.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// The Android client: a second Android phone that does what the iPhone app does (calls, texts, contacts, recents,
// switches) with the Android phone that holds the SIMs. Talks protocol/PROTOCOL.md as a BLE central.
android {
    namespace = "app.tether.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tether.client"
        minSdk = 29          // L2CAP connection-oriented channels (BluetoothDevice.createInsecureL2capChannel)
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        localProps.getProperty("tether.signing.storeFile")?.let { store ->
            create("own") {
                storeFile = file(store)
                storePassword = localProps.getProperty("tether.signing.storePassword")
                keyAlias = localProps.getProperty("tether.signing.keyAlias")
                keyPassword = localProps.getProperty("tether.signing.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("own") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false }
    sourceSets["test"].resources.srcDir("../../protocol")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.4")   // reading the pairing QR code
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
