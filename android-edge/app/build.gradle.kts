import java.util.Properties

// Load local.properties manually — project.findProperty() does NOT read local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aura.edge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aura.edge"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "4.0.0"

        // Gemini API key — set in local.properties: GEMINI_API_KEY=your_key
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\""
        )

        // Cloud Run URL — set in local.properties after deploying: GUARDRAIL_API_URL=https://...
        buildConfigField(
            "String",
            "GUARDRAIL_API_URL",
            "\"${localProperties.getProperty("GUARDRAIL_API_URL", "")}\""
        )
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
}

dependencies {
    // ── Core Android ──
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // ── Kotlin Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── WebSocket (OkHttp) — raw bidi-streaming to Gemini Live ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── JSON serialization ──
    implementation("com.google.code.gson:gson:2.11.0")

    // ── Firebase / GCP Telemetry (Phase 5 — uncomment after firebase setup) ──
    // implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    // implementation("com.google.firebase:firebase-firestore")
    // implementation("com.google.firebase:firebase-storage")

    // ── Testing ──
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
