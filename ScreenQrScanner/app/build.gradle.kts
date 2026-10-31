plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.screenqr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screenqr"
        minSdk = 24          // Android 7.0 及以上
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/native-image/**",
                "META-INF/versions/**"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "engine"
    productFlavors {
        create("dual") {
            dimension = "engine"
            versionNameSuffix = "-双引擎"
        }
        create("wechat") {
            dimension = "engine"
            versionNameSuffix = "-微信"
        }
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // 解码改用 native zxing-cpp（jniLibs 内 libzxingcpp_android.so）
    // 微信 QRCode 引擎（JavaCV 预编译 opencv_contrib，含 wechat_qrcode 模块）
    // 注意：opencv 的 JNI 库链接了 OpenBLAS，必须带上 openblas 平台包，
    // 否则 dlopen 报 libjniopenblas_nolapack.so not found → 引擎完全无法加载
    implementation("org.bytedeco:javacv:1.5.11")
    implementation("org.bytedeco:javacpp:1.5.11")
    implementation("org.bytedeco:openblas:0.3.28-1.5.11")
    implementation("org.bytedeco:openblas:0.3.28-1.5.11:android-arm64")
    implementation("org.bytedeco:opencv:4.10.0-1.5.11")
    implementation("org.bytedeco:opencv:4.10.0-1.5.11:android-arm64")
}
