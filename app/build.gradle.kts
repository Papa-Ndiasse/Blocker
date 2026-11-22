plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val resolvedVersionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: (project.findProperty("versionCode") as? String)?.toIntOrNull()
    ?: 1).coerceAtLeast(1)

val resolvedVersionName = System.getenv("VERSION_NAME")
    ?: (project.findProperty("versionName") as? String)
    ?: "1.0.$resolvedVersionCode"

val resolvedUpdateToken = (System.getenv("GITHUB_UPDATE_TOKEN")
    ?: (project.findProperty("githubUpdateToken") as? String)
    ?: "").replace("\"", "\\\"")

val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    ?: (project.findProperty("signingKeystorePath") as? String)

android {
    namespace = "com.blocker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blocker.app"
        minSdk = 29
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        buildConfigField("String", "GITHUB_REPO", "\"Papa-Ndiasse/Blocker\"")
        buildConfigField("String", "GITHUB_UPDATE_TOKEN", "\"$resolvedUpdateToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                    ?: (project.findProperty("signingKeystorePassword") as? String) ?: ""
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    ?: (project.findProperty("signingKeyAlias") as? String) ?: ""
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                    ?: (project.findProperty("signingKeyPassword") as? String) ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
