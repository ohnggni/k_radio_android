import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

// 서명 정보는 Git에 올라가지 않는 keystore.properties에서 읽음
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 채널 설정 암호화 키·주소는 공개 저장소에 올리지 않음:
// 프로젝트 옆 비공개 저장소(../kradio-private/secrets.properties) 또는 프로젝트 루트 secrets.properties
val secretsProps = Properties().apply {
    val f = listOf(
        rootProject.file("secrets.properties"),
        rootProject.file("../kradio-private/secrets.properties"),
    ).firstOrNull { it.exists() }
        ?: throw GradleException("secrets.properties 없음: ~/AndroidStudioProjects/kradio-private 를 받아 두세요")
    f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    secretsProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("secrets.properties 에 $name 없음")
// 키를 그대로 넣지 않고 마스크와 XOR한 두 조각으로 넣음 (문자열 검색으로 바로 안 보이게)
val configKeyParts: Pair<String, String> = run {
    val key = Base64.getDecoder().decode(secret("CONFIG_KEY"))
    require(key.size == 32) { "CONFIG_KEY 는 32바이트여야 함" }
    val mask = MessageDigest.getInstance("SHA-256").digest(("kr-" + secret("CONFIG_FILE")).toByteArray())
    val mixed = ByteArray(32) { (key[it].toInt() xor mask[it].toInt()).toByte() }
    Base64.getEncoder().encodeToString(mask) to Base64.getEncoder().encodeToString(mixed)
}
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.ohnggni.kradio"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "kr.ohnggni.kradio"
        minSdk = 26
        targetSdk = 36   // 안드로이드 17 백그라운드 오디오 '사용 중' 권한 요구 회피 (컴파일은 37 유지)
        versionCode = 24
        versionName = "1.11.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CONFIG_URL", "\"${secret("CONFIG_URL")}\"")
        buildConfigField("String", "CK_A", "\"${configKeyParts.first}\"")
        buildConfigField("String", "CK_B", "\"${configKeyParts.second}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProps.getProperty("storeFile")?.let { file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"   // 정식 버전과 별도 앱으로 설치
            versionNameSuffix = "-dev"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.reorderable)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}