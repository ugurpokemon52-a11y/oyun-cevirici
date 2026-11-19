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

    // Iki ayri uygulama: telefon (gonderici, tum ayarlar) ve TV (sadece alici)
    flavorDimensions += "cihaz"
    productFlavors {
        create("mobil") {
            dimension = "cihaz"
        }
        create("tv") {
            dimension = "cihaz"
            applicationIdSuffix = ".tv"
        }
    }

    // Imza dosyasinin sifresi depoda DEGIL, GitHub'in gizli ayarlarinda (KEYSTORE_PASS).
    val ksPass = System.getenv("KEYSTORE_PASS")
    signingConfigs {
        if (!ksPass.isNullOrBlank()) {
            create("sabit") {
                storeFile = file("imza.p12")
                storePassword = ksPass
                keyAlias = "cevirici"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Sifre yoksa (yerel derleme) hata ayiklama imzasiyla imzalanir
            signingConfig = if (!ksPass.isNullOrBlank()) signingConfigs.getByName("sabit")
            else signingConfigs.getByName("debug")
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
    "mobilImplementation"("com.google.mlkit:text-recognition:16.0.1")
    "mobilImplementation"("com.google.mlkit:text-recognition-japanese:16.0.1")
    "mobilImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")
    "mobilImplementation"("com.google.mlkit:text-recognition-korean:16.0.1")
    // Ceviri (dil paketi bir kez indirilir, sonra internetsiz)
    "mobilImplementation"("com.google.mlkit:translate:17.0.3")
    // Kamera modu
    "mobilImplementation"("androidx.activity:activity:1.8.2")
    "mobilImplementation"("androidx.camera:camera-core:1.3.4")
    "mobilImplementation"("androidx.camera:camera-camera2:1.3.4")
    "mobilImplementation"("androidx.camera:camera-lifecycle:1.3.4")
    "mobilImplementation"("androidx.camera:camera-view:1.3.4")
}
