plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xs.repair"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xs.repair"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.1"
    }

    /**
     * 固定签名：以前用 CI 自动生成的 debug 签名，每次打包签名都不一样 →
     * 手机上装新版必须先卸载旧的（还会丢登录态和记住的服务器地址）。
     * 现在固定用仓库里的 keystore/app.p12，以后可以直接覆盖安装升级。
     */
    signingConfigs {
        create("fixed") {
            storeFile = file("../keystore/app.p12")
            storePassword = "xsrepair2026"
            keyAlias = "xsrepair"
            keyPassword = "xsrepair2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
