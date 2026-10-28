import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun secret(name: String): String =
    (System.getenv(name) ?: localProps.getProperty(name) ?: "").replace("\"", "")

android {
    namespace = "com.iskezis.myst"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iskezis.myst"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${secret("SPOTIFY_CLIENT_ID")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"${secret("SPOTIFY_CLIENT_SECRET")}\"")
    }

    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        create("tv") {
            dimension = "device"
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/kotlinx_coroutines_core.version",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.android.material)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.multidex)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.coil)
    implementation(libs.okhttp)
    implementation(libs.shimmer)
    "tvImplementation"(libs.androidx.leanback)
}
