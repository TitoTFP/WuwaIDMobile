import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

val releaseStorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningAvailable = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

val verifyUpgradeSigning = tasks.register("verifyUpgradeSigning") {
    notCompatibleWithConfigurationCache("Validates the original private signing keystore at execution time.")
    doLast {
        check(releaseSigningAvailable) { "Release requires the original ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD; never replace the published signing key." }
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        file(requireNotNull(releaseStorePath)).inputStream().use {
            store.load(it, requireNotNull(releaseStorePassword).toCharArray())
        }
        val certificate = store.getCertificate(requireNotNull(releaseKeyAlias))
            ?: error("Release signing alias has no certificate")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        check(fingerprint == "2cb1b4fed5446cf52018b5d56d58c379d7328022c1ba17278c19c0002e484df9") {
            "Release certificate differs from published WuwaIDMobile APKs; an in-place upgrade would fail."
        }
        check(tauriProperties.getProperty("tauri.android.versionCode", "0").toInt() > 7) {
            "Release versionCode must exceed the last pre-Tauri release (7)."
        }
    }
}
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("ReleaseBuild") }
    .configureEach { dependsOn(verifyUpgradeSigning) }

// Incremental ZIP replacement retains obsolete native-library data in debug APKs.
// Repackage only debug APKs from all inputs; compilation and other tasks stay incremental.
tasks.matching { it.name.startsWith("package") && it.name.endsWith("Debug") }
    .configureEach { outputs.upToDateWhen { false } }

android {
    compileSdk = 36
    namespace = "com.titotfp.wuwaid"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "com.titotfp.wuwaid"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()
        versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")
    }
    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            optimization {
               enable = true
            }
            proguardFiles(
                *fileTree(".") {
                  include("**/*.pro")
                  exclude("build/**")
                }.files.toTypedArray()
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("wm26.sdk", android.sdkDirectory.absolutePath)
            it.systemProperty("wm26.ndk", providers.environmentVariable("NDK_HOME").orElse("").get())
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

rust {
    rootDirRel = "../../../"
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    // Axeron's AIDL includes the same Shizuku 13 wire contract; package it only once.
    implementation("dev.rikka.shizuku:api:13.1.5") {
        exclude(group = "dev.rikka.shizuku", module = "aidl")
    }
    implementation("dev.rikka.shizuku:provider:13.1.5") {
        exclude(group = "dev.rikka.shizuku", module = "aidl")
    }
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.fahrez182.Axeron-API:axsh:3.0.0")
    implementation("com.github.fahrez182.Axeron-API:aidl:3.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("com.android.tools.build:apksig:8.13.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-json-org:2.15.3") {
        exclude(group = "org.json", module = "json")
    }
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = file("tauri.build.gradle.kts"))
