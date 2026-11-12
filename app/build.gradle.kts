plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.norbury.taskalarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.norbury.takt"
        minSdk = 26          // 动态取色需要 Android 8+，但核心动态色彩自 Android 12 起
        targetSdk = 35       // 小米 15 运行 Android 15
        versionCode = 234
        versionName = "3.34.139"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
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
    buildFeatures {
        viewBinding = true
    }

    // APK 文件名带版本号：TaskAlarm-v3.5.0-debug.apk，方便区分新旧包
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "Taktu-v${versionName}-${buildType.name}.apk"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Material 3 Expressive 组件库（含 DynamicColors 动态取色）
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 局域网控制：内置 HTTP server（久经考验的极简实现，无传递依赖）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 磨砂玻璃：实时抓底层内容做高斯模糊（backdrop blur，View 体系唯一正路）
    implementation("com.github.Dimezis:BlurView:version-2.0.3")

    // 本地单元测试：直接验证 TaskImporter 的容错解析（沙箱内无模拟器，用单测兜底）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
