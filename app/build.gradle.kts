import java.util.Properties

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
        versionName = "0.7.0"
    }
    val signingProps = Properties().apply {
        val f = File(System.getProperty("user.home"), ".config/lazycar/signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    // Phone GO: the one-tap "Turn on location" dialog (SettingsClient resolution); no public API besides it.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // UVC webcam (reverse camera). 3.3.x is broken on JitPack; immersionbar and webpdecoder are dead JCenter deps.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7") {
        exclude(group = "com.gyf.immersionbar")
        exclude(group = "com.zlc.glide")
    }
    testImplementation("junit:junit:4.13.2")
}
