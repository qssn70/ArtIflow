import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
  val localFile = rootProject.file("local.properties")
  if (localFile.exists()) {
    localFile.inputStream().reader(Charsets.UTF_8).use { reader ->
      load(reader)
    }
  }
}

fun localValue(key: String, defaultValue: String = ""): String {
  return localProperties.getProperty(key)?.trim().orEmpty().ifEmpty { defaultValue }
}

fun String.toBuildConfigString(): String {
  val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
  return "\"$escaped\""
}

android {
  namespace = "com.studysuit.aiqa"
  compileSdk = 35

  defaultConfig {
    val arkApiKey = localValue("ARK_API_KEY")
    val arkModel = localValue("ARK_MODEL", "doubao-seed-1-8-251228")
    val arkBaseUrl = localValue("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3")
    val arkEndpoint = localValue("ARK_ENDPOINT", "responses")
    val arkSystemPrompt = localValue(
      "ARK_SYSTEM_PROMPT",
      "你是一个有用的AI学习辅导助手，擅长把复杂知识点讲清楚，优先给步骤化解释。"
    )
    val openSpeechApiKey = localValue("OPENSPEECH_API_KEY")
    val openSpeechResourceId = localValue("OPENSPEECH_RESOURCE_ID", "volc.seedasr.auc")
    val openSpeechSubmitUrl = localValue(
      "OPENSPEECH_SUBMIT_URL",
      "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"
    )
    val openSpeechQueryUrl = localValue(
      "OPENSPEECH_QUERY_URL",
      "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"
    )
    val openSpeechAudioUrl = localValue(
      "OPENSPEECH_AUDIO_URL",
      "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/console/bigtts/zh_female_cancan_mars_bigtts.mp3"
    )
    val openSpeechUid = localValue("OPENSPEECH_UID", "豆包语音")

    applicationId = "com.studysuit.aiqa"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "ARK_API_KEY", arkApiKey.toBuildConfigString())
    buildConfigField("String", "ARK_MODEL", arkModel.toBuildConfigString())
    buildConfigField("String", "ARK_BASE_URL", arkBaseUrl.toBuildConfigString())
    buildConfigField("String", "ARK_ENDPOINT", arkEndpoint.toBuildConfigString())
    buildConfigField("String", "ARK_SYSTEM_PROMPT", arkSystemPrompt.toBuildConfigString())
    buildConfigField("String", "OPENSPEECH_API_KEY", openSpeechApiKey.toBuildConfigString())
    buildConfigField("String", "OPENSPEECH_RESOURCE_ID", openSpeechResourceId.toBuildConfigString())
    buildConfigField("String", "OPENSPEECH_SUBMIT_URL", openSpeechSubmitUrl.toBuildConfigString())
    buildConfigField("String", "OPENSPEECH_QUERY_URL", openSpeechQueryUrl.toBuildConfigString())
    buildConfigField("String", "OPENSPEECH_AUDIO_URL", openSpeechAudioUrl.toBuildConfigString())
    buildConfigField("String", "OPENSPEECH_UID", openSpeechUid.toBuildConfigString())
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
  implementation("com.google.android.material:material:1.12.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  debugImplementation("androidx.compose.ui:ui-tooling")
  debugImplementation("androidx.compose.ui:ui-test-manifest")

  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
