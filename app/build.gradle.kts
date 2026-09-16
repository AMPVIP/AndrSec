plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.andrsec"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        buildConfigField(
            "String",
            "BOT_TOKEN",
            "\"${project.findProperty("VK_BOT_TOKEN") ?: ""}\""
        )
        buildConfigField(
            "long",
            "VK_GROUP_ID",
            "${project.findProperty("VK_GROUP_ID") ?: 0}L"
        )
        buildConfigField(
            "long",
            "VK_ALLOWED_PEER",
            "${project.findProperty("VK_ALLOWED_PEER") ?: 0}L"
        )
        applicationId = "com.example.andrsec"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")


}