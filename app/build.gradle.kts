import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release 签名（发布准备 C5）：密钥只从环境变量（SUBTRACK_RELEASE_*）或
// 未跟踪的 keystore.properties 读取，两者都不提交。都没有时 release 构建按
// 未签名产物输出（app-release-unsigned.apk），不生成临时 key 冒充正式签名。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun releaseSigningValue(key: String): String? =
    System.getenv("SUBTRACK_RELEASE_$key") ?: keystoreProps.getProperty(key)
val hasReleaseSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .all { !releaseSigningValue(it).isNullOrBlank() }

android {
    sourceSets { getByName("androidTest").assets.srcDirs(files("$projectDir/schemas")) }
    namespace = "com.subtrack"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.subtrack"
        minSdk = 26
        targetSdk = 35
        versionCode = 62
        versionName = "1.9.5"
        val updateKey = System.getenv("SUBTRACK_UPDATE_PUBLIC_KEY") ?: ""
        require(updateKey.matches(Regex("[A-Za-z0-9+/=]*"))) { "Update public key must be base64 X509 DER" }
        buildConfigField("String", "UPDATE_PUBLIC_KEY", "\"$updateKey\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"direct\"")
        // 公开更新通道：GitHub Releases。owner/repo 随构建注入，默认指向本项目的公开仓库。
        val updateRepo = (System.getenv("SUBTRACK_UPDATE_REPO")
            ?: runCatching { providers.gradleProperty("subtrackUpdateRepo").get() }.getOrNull()
            ?: "nimeng1222/subtrack-android").trim()
        require(updateRepo.isEmpty() || updateRepo.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+"))) {
            "SUBTRACK_UPDATE_REPO must be owner/repo"
        }
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"$updateRepo\"")
        testInstrumentationRunner = "com.subtrack.Round2Instrumentation"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningValue("STORE_FILE")!!)
                storePassword = releaseSigningValue("STORE_PASSWORD")
                keyAlias = releaseSigningValue("KEY_ALIAS")
                keyPassword = releaseSigningValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("SubTrack: 未找到 SUBTRACK_RELEASE_* 环境变量或 keystore.properties，release 将输出未签名产物（app-release-unsigned.apk）。")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    // 桌面小组件：Glance（只做 appwidget + material3，不引 glance-preview）
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // 应用锁（v1.1）。
    // biometric 内部用 FragmentActivity，会把 androidx.fragment 传递依赖进来；
    // 若让它解析到 1.2.5（2020 年版），与 activity-compose 1.10 的
    // ActivityResultRegistry 不兼容 → 所有 SAF 启动器（导出/导入）会在点击时
    // 抛 "Can only use lower 16 bits for requestCode" 崩溃。
    // 必须显式把 fragment 提到与 activity 同代的版本。
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
