import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 发布签名材料放在仓库根目录的 keystore.properties，密钥库放 keystore/ 下。
// 两者都被 .gitignore 排除：构建机器上没有这两个文件时，release 依然能构建成功，只是不签名。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()

android {
    namespace = "com.changakira.screenshotbye"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.changakira.screenshotbye"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            // Robolectric 要读真实 Manifest、资源和布局，缺这些会在运行期报错
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.recyclerview)
    // 旋钮的逐档无障碍焦点靠 ExploreByTouchHelper，它是 customview 里的类
    implementation(libs.androidx.customview)
    implementation(libs.androidx.work.runtime.ktx)

    // 沙箱里没有 /dev/kvm，起不了模拟器；Robolectric 是唯一能真正跑一遍启动路径、
    // 通知构造和队列卫生的手段。
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}
