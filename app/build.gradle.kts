plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val clerkPublishableKey = providers.environmentVariable("CLERK_PUBLISHABLE_KEY")
    .orElse(providers.gradleProperty("CLERK_PUBLISHABLE_KEY"))
    .getOrElse("pk_test_dW5pcXVlLWFhcmR2YXJrLTk4MDQuY2xlcmsuYWNjb3VudHMuZGV2JA")
require(clerkPublishableKey.isEmpty() || clerkPublishableKey.matches(Regex("pk_(test|live)_[A-Za-z0-9]+"))) {
    "Invalid Clerk publishable key"
}

android {
    namespace = "org.buddytools.talkt"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.buddytools.talkt"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "DEFAULT_API_URL", "\"https://talkt-api.oddofrancesco000.workers.dev\"")
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", "\"$clerkPublishableKey\"")
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
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "onnx"
        ignoreAssetsPattern = "parakeet-tdt-v3:whisper-tiny"
    }
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.clerk:clerk-android-api:1.1.5")
    implementation("com.clerk:clerk-android-ui:1.1.5")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
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
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
