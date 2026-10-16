plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lazyneil.lazycar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lazyneil.lazycar"
        minSdk = 31
        targetSdk = 35
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "0.6.6"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Visible name is CarApp; applicationId stays com.lazyneil.lazycar so installs upgrade in place.
android.applicationVariants.all {
    outputs.all { (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "CarApp.apk" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.14.0")
    // UVC webcam (reverse camera). 3.3.x is broken on JitPack; immersionbar and webpdecoder are dead JCenter deps.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7") {
        exclude(group = "com.gyf.immersionbar")
        exclude(group = "com.zlc.glide")
    }
    testImplementation("junit:junit:4.13.2")
}
