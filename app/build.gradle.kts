plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sweatsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sweatsync"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Release signing comes from Gradle properties kept outside the repo (~/.gradle/gradle.properties);
    // without them the release APK is built unsigned.
    val keystore = providers.gradleProperty("sweatsync.keystore").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.gradleProperty("sweatsync.keystore.password").get()
                keyAlias = providers.gradleProperty("sweatsync.key.alias").get()
                keyPassword = providers.gradleProperty("sweatsync.key.password").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
