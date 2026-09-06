import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val verifyLocalAsrModel by tasks.registering {
    val manifestFile = rootProject.file("models/parakeet-tdt-v3.json")
    inputs.file(manifestFile)
    doLast {
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        val directory = file("src/main/assets/models/${manifest["directory"]}")
        for (entry in manifest["files"] as List<*>) {
            val model = entry as Map<*, *>
            val path = directory.resolve(model["file"].toString())
            check(path.isFile && path.length() == (model["bytes"] as Number).toLong()) {
                "Missing or invalid local ASR model: ${path.name}. Run bun run models:download."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            path.inputStream().buffered().use { stream ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == model["sha256"]) {
                "Local ASR checksum mismatch: ${path.name}. Run bun run models:download."
            }
        }
    }
}

tasks.matching { it.name.matches(Regex("merge(Debug|Release)Assets")) }.configureEach {
    dependsOn(verifyLocalAsrModel)
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
