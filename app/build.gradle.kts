plugins {
    id("com.android.application")
}

android {
    namespace = "de.schumey.overhead"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.schumey.overhead"
        minSdk = 30
        // Sideloaded APK: target 34 keeps the layout below the system bars (no forced edge-to-edge).
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload only: signed with the local debug key so the APK installs without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json is only a stub on the JVM; the real implementation is needed to test the parsers.
    testImplementation("org.json:json:20250517")
}
