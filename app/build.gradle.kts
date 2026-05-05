import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
}

// local.properties読込
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())

android {
    namespace = "com.kohei.summaryrecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kohei.summaryrecorder"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String", "GROQ_API_KEY",
            "\"${localProps.getProperty("groq.api.key", "")}\""
        )
        buildConfigField(
            "String", "GEMINI_API_KEY",
            "\"${localProps.getProperty("gemini.api.key", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // JUnit5 for unit tests
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-android-compiler:2.54")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

    // Hilt Navigation Compose (hiltViewModel用)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gemini SDK
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // MockWebServer (debug builds only: E2E/Maestro用)
    debugImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.10.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:runner:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.12")
    testImplementation("com.google.dagger:hilt-android-testing:2.54")
    kspTest("com.google.dagger:hilt-android-compiler:2.54")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.work:work-testing:2.9.0")
}
