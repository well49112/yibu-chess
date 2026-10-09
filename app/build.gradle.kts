plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}
// 1.23.2 can execute unsupported SME2 instructions on Xiaomi 17 / Android 16.
// 1.24.3 is the Android Maven release verified by upstream for this device family.
val onnxRuntimeVersion = "1.24.3"
android {
    namespace = "cn.yibu.chess"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.yibu.chess"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.9.1"
        buildConfigField("String", "ONNX_RUNTIME_VERSION", "\"$onnxRuntimeVersion\"")
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("personal") {
            val keyPath = providers.environmentVariable("YIBU_KEYSTORE").orNull
            storeFile = file(keyPath ?: "../signing/personal.jks")
            storePassword = providers.environmentVariable("YIBU_STORE_PASSWORD").orNull ?: "yibu-personal-test"
            keyAlias = "yibu"
            keyPassword = providers.environmentVariable("YIBU_KEY_PASSWORD").orNull ?: "yibu-personal-test"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("personal") }
        release {
            signingConfig = signingConfigs.getByName("personal")
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("../core/src/test/resources")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compress native libraries to keep the full offline APK below GitHub's 100 MiB limit.
        // Android extracts them at installation; the libraries themselves retain 16 KB alignment.
        jniLibs.useLegacyPackaging = true
    }
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g" // Robolectric's Android SDKs and Compose need more than Gradle's 512 MiB default.
    testLogging.events("started", "passed", "failed", "skipped")
    providers.gradleProperty("startupNativeDir").orNull?.let { directory ->
        // JNI libraries cannot be loaded into two Robolectric sandbox classloaders in one JVM.
        forkEvery = 1
        jvmArgs("-Djava.library.path=$directory", "-Dstartup.native=true")
    }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntimeVersion")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
