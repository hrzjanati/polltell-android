import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release key from local.properties (git-ignored); without one, release builds use the debug key.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.tether"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tether.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
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
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = false
        error += "NewApi"
    }
    sourceSets["test"].resources.srcDir("../../protocol")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":helper"))
    implementation(project(":core")) // protocol and codecs, shared with the Android client
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
