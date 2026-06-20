plugins {
  id("com.android.test")
  id("org.jetbrains.kotlin.android")
  id("androidx.baselineprofile")
}

android {
  namespace = "com.studysuit.aiqa.benchmark"
  compileSdk = 35

  defaultConfig {
    minSdk = 28
    targetSdk = 28
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  targetProjectPath = ":app"

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation("androidx.test.ext:junit:1.2.1")
  implementation("androidx.test.uiautomator:uiautomator:2.3.0")
  implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
}
