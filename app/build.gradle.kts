plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val talktAppToken = System.getenv("TALKT_APP_TOKEN").orEmpty().ifBlank {
    val devVars = rootProject.file("worker/.dev.vars")
    if (devVars.exists()) {
        devVars.readLines()
            .firstOrNull { it.startsWith("TALKT_APP_TOKEN=") }
            ?.substringAfter("=")
            ?.trim()
            ?.removeSurrounding("\"")
            .orEmpty()
    } else {
        ""
    }
}

android {
    namespace = "org.buddytools.talkt"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.buddytools.talkt"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "DEFAULT_API_URL", "\"https://talkt-api.oddofrancesco000.workers.dev\"")
        buildConfigField("String", "DEFAULT_APP_TOKEN", "\"$talktAppToken\"")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
