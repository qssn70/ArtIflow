plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.studysuit.aiqa"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.studysuit.aiqa"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.14"
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.03"))
  androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))

  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
  implementation("androidx.activity:activity-compose:1.9.2")

  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.material3:material3")
  implementation("io.noties.markwon:core:4.6.2")
  implementation("io.noties.markwon:inline-parser:4.6.2")
  implementation("io.noties.markwon:ext-latex:4.6.2")
  implementation("com.google.android.material:material:1.12.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.mlkit:text-recognition:16.0.1")
  implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
  implementation("androidx.work:work-runtime-ktx:2.9.1")

  debugImplementation("androidx.compose.ui:ui-tooling")
  debugImplementation("androidx.compose.ui:ui-test-manifest")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
  testImplementation("org.json:json:20240303")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:rules:1.6.1")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
