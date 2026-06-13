plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.trackhub"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Official Google Play Install Referrer Library (attribution source)
    implementation("com.android.installreferrer:installreferrer:2.2")
    testImplementation("junit:junit:4.13.2")
}

// Publish to GitHub Packages / Maven for consumers:
//   ./gradlew :trackhub:publish
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.trackhub"
            artifactId = "trackhub-android"
            version = "1.0.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
