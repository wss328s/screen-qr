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
        // 只保留 arm64-v8a：目标设备(DJI RC Pro / 小米)均为 arm64，裁掉 x86/armv7 省体积
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // 裁剪 OpenCV 无用模块：只保留微信引擎依赖链
            // wechat_qrcode -> dnn -> imgproc -> core + openblas(nolapack) + zxingcpp
            excludes += setOf(
                "lib/arm64-v8a/libjnicvkernels.so",
                "lib/arm64-v8a/libjniopenblas.so",
                "lib/arm64-v8a/libjniopencv_aruco.so",
                "lib/arm64-v8a/libjniopencv_bgsegm.so",
                "lib/arm64-v8a/libjniopencv_bioinspired.so",
                "lib/arm64-v8a/libjniopencv_calib3d.so",
                "lib/arm64-v8a/libjniopencv_dnn_superres.so",
                "lib/arm64-v8a/libjniopencv_face.so",
                "lib/arm64-v8a/libjniopencv_features2d.so",
                "lib/arm64-v8a/libjniopencv_flann.so",
                "lib/arm64-v8a/libjniopencv_highgui.so",
                "lib/arm64-v8a/libjniopencv_img_hash.so",
                "lib/arm64-v8a/libjniopencv_imgcodecs.so",
                "lib/arm64-v8a/libjniopencv_intensity_transform.so",
                "lib/arm64-v8a/libjniopencv_java.so",
                "lib/arm64-v8a/libjniopencv_mcc.so",
                "lib/arm64-v8a/libjniopencv_ml.so",
                "lib/arm64-v8a/libjniopencv_objdetect.so",
                "lib/arm64-v8a/libjniopencv_optflow.so",
                "lib/arm64-v8a/libjniopencv_phase_unwrapping.so",
                "lib/arm64-v8a/libjniopencv_photo.so",
                "lib/arm64-v8a/libjniopencv_plot.so",
                "lib/arm64-v8a/libjniopencv_python3.so",
                "lib/arm64-v8a/libjniopencv_quality.so",
                "lib/arm64-v8a/libjniopencv_rapid.so",
                "lib/arm64-v8a/libjniopencv_saliency.so",
                "lib/arm64-v8a/libjniopencv_shape.so",
                "lib/arm64-v8a/libjniopencv_signal.so",
                "lib/arm64-v8a/libjniopencv_stitching.so",
                "lib/arm64-v8a/libjniopencv_structured_light.so",
                "lib/arm64-v8a/libjniopencv_superres.so",
                "lib/arm64-v8a/libjniopencv_text.so",
                "lib/arm64-v8a/libjniopencv_tracking.so",
                "lib/arm64-v8a/libjniopencv_video.so",
                "lib/arm64-v8a/libjniopencv_videoio.so",
                "lib/arm64-v8a/libjniopencv_videostab.so",
                "lib/arm64-v8a/libjniopencv_xfeatures2d.so",
                "lib/arm64-v8a/libjniopencv_ximgproc.so",
                "lib/arm64-v8a/libjniopencv_xphoto.so",
                "lib/arm64-v8a/libopencv_aruco.so",
                "lib/arm64-v8a/libopencv_bgsegm.so",
                "lib/arm64-v8a/libopencv_bioinspired.so",
                "lib/arm64-v8a/libopencv_calib3d.so",
                "lib/arm64-v8a/libopencv_dnn_superres.so",
                "lib/arm64-v8a/libopencv_face.so",
                "lib/arm64-v8a/libopencv_features2d.so",
                "lib/arm64-v8a/libopencv_flann.so",
                "lib/arm64-v8a/libopencv_highgui.so",
                "lib/arm64-v8a/libopencv_img_hash.so",
                "lib/arm64-v8a/libopencv_imgcodecs.so",
                "lib/arm64-v8a/libopencv_intensity_transform.so",
                "lib/arm64-v8a/libopencv_java.so",
                "lib/arm64-v8a/libopencv_mcc.so",
                "lib/arm64-v8a/libopencv_ml.so",
                "lib/arm64-v8a/libopencv_objdetect.so",
                "lib/arm64-v8a/libopencv_optflow.so",
                "lib/arm64-v8a/libopencv_phase_unwrapping.so",
                "lib/arm64-v8a/libopencv_photo.so",
                "lib/arm64-v8a/libopencv_plot.so",
                "lib/arm64-v8a/libopencv_quality.so",
                "lib/arm64-v8a/libopencv_rapid.so",
                "lib/arm64-v8a/libopencv_saliency.so",
                "lib/arm64-v8a/libopencv_shape.so",
                "lib/arm64-v8a/libopencv_signal.so",
                "lib/arm64-v8a/libopencv_stitching.so",
                "lib/arm64-v8a/libopencv_structured_light.so",
                "lib/arm64-v8a/libopencv_superres.so",
                "lib/arm64-v8a/libopencv_text.so",
                "lib/arm64-v8a/libopencv_tracking.so",
                "lib/arm64-v8a/libopencv_video.so",
                "lib/arm64-v8a/libopencv_videoio.so",
                "lib/arm64-v8a/libopencv_videostab.so",
                "lib/arm64-v8a/libopencv_xfeatures2d.so",
                "lib/arm64-v8a/libopencv_ximgproc.so",
                "lib/arm64-v8a/libopencv_xphoto.so",
                "lib/armeabi-v7a/libzxingcpp_android.so",
                "lib/x86/libzxingcpp_android.so",
                "lib/x86_64/libzxingcpp_android.so",
                // JavaCPP 依赖带进 jniLibs 目录的无用资源：头文件、haarcascade 人脸模型、cmake 等
                "lib/arm64-v8a/include/**",
                "lib/arm64-v8a/sdk/**",
                "lib/arm64-v8a/*.h",
                "lib/arm64-v8a/*.hpp",
                "lib/arm64-v8a/*.mk",
                "lib/arm64-v8a/*.pc",
                "lib/arm64-v8a/*.cmake"
            )
        }
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
