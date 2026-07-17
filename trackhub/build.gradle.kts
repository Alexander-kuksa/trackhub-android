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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // Optional at runtime: TrackHub reads GAID only when the host explicitly
    // enables collection and grants ad_user_data consent.
    implementation("com.google.android.gms:play-services-ads-identifier:18.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    // The manifest names AndroidJUnitRunner directly; ext:junit provides the
    // AndroidJUnit4 bridge but does not package the instrumentation runner.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Publish to GitHub Packages / Maven for consumers:
//   ./gradlew :trackhub:publish
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.trackhub"
            artifactId = "trackhub-android"
            version = "1.5.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
