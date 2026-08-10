plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.trackhub"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    publishing {
        singleVariant("release") {}
    }
}

dependencies {
    // Official Google Play Install Referrer Library (attribution source)
    implementation("com.android.installreferrer:installreferrer:2.2")
    // Optional at runtime: do not force AD_ID permission or a minSdk increase
    // on every consumer. Hosts that explicitly enable GAID collection add the
    // runtime dependency themselves (documented in README). 18.2.0 is the
    // official Google artifact whose manifest still supports minSdk 21.
    compileOnly("com.google.android.gms:play-services-ads-identifier:18.2.0")
    // Optional fallback identifier required by Google App Conversion when an
    // advertising ID is unavailable or limited. Hosts that enable identifier
    // collection add the same artifact at runtime (see README).
    compileOnly("com.google.android.gms:play-services-appset:16.1.0")
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
            // JitPack supplies these properties for the requested Git tag.
            // Local publishing keeps stable TrackHub defaults.
            groupId = providers.gradleProperty("group").orElse("com.trackhub").get()
            artifactId = "trackhub-android"
            version = providers.gradleProperty("version").orElse("3.0.1").get()
            afterEvaluate { from(components["release"]) }
        }
    }
}
