plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.nowonna.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.nowonna.car"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 서버 주소: gradle.properties 의 nowonna.serverUrl (없으면 에뮬레이터 → 이 맥의 server-lite)
        // https 주소를 쓰면 평문(http) 통신은 자동으로 막힌다.
        val serverUrl = (project.findProperty("nowonna.serverUrl") as String?)?.trimEnd('/') ?: "http://10.0.2.2:8787"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        manifestPlaceholders["cleartext"] = (!serverUrl.startsWith("https://")).toString()
    }

    buildTypes {
        debug {
            // 시연 버튼(통화 종료·도착·탑승·변경 요청 시연)과 SDK 확인 화면
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEV_TOOLS", "false")
            // 제출용 APK를 바로 설치할 수 있게 디버그 키로 서명
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
