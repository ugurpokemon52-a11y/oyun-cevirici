plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.huseyin.oyuncevirici"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.huseyin.oyuncevirici"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    signingConfigs {
        create("sabit") {
            storeFile = file("cevirici.keystore")
            storePassword = "cevirici123"
            keyAlias = "cevirici"
            keyPassword = "cevirici123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sabit")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sabit")
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
    // Ekrandaki yaziyi okuma (cihaz icinde, internetsiz)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // Ceviri (dil paketi bir kez indirilir, sonra internetsiz)
    implementation("com.google.mlkit:translate:17.0.3")
}
