/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("UnstableApiUsage", "SpellCheckingInspection")

import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.util.Properties

plugins {
    // Gradle doesn't allow conditionally enabling/disabling plugins
    id("com.android.application")
    id("com.mikepenz.aboutlibraries.plugin")
    kotlin("android")
    kotlin("kapt")
    id("com.google.devtools.ksp") version "2.0.0-1.0.23"
}
android {
    // functions to get git info: gitCommitHash, gitBranch, gitRemote
    val gitCommitHash = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().toString().trim()
    val gitBranch = providers.exec {
        commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
    }.standardOutput.asText.get().toString().trim()
    val gitRemote = providers.exec {
        commandLine("git", "config", "--get", "remote.origin.url")
    }.standardOutput.asText.get().toString().trim()
    val timestamp = System.currentTimeMillis()

    namespace = "com.fox2code.mmm"
    compileSdk = 34
    ndkVersion = "25.2.9519653"
    signingConfigs {
        create("release") {
            if (File("signing.properties").exists()) {
                val properties = Properties().apply {
                    load(File("signing.properties").reader())
                }
                storeFile = File(properties.getProperty("storeFilePath"))
                storePassword = properties.getProperty("storePassword")
                keyPassword = properties.getProperty("keyPassword")
                keyAlias = properties.getProperty("keyAlias")
            }
        }
    }
    defaultConfig {
        applicationId = "com.fox2code.mmm"
        minSdk = 26
        targetSdk = 34
        versionCode = 92
        versionName = "2.3.7"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true
        resourceConfigurations.addAll(
            listOf(
                "ar",
                "bs",
                "cs",
                "de",
                "es-rMX",
                "es",
                "el",
                "fr",
                "hu",
                "id",
                "it",
                "ja",
                "nl",
                "pl",
                "pt",
                "pt-rBR",
                "ru",
                "tr",
                "uk",
                "vi",
                "zh",
                "zh-rTW",
                "en"
            )
        )
        ksp {
            arg("room.schemaLocation", "$projectDir/roomSchemas")
        }
    }

    splits {

        // Configures multiple APKs based on ABI.
        abi {

            // Enables building multiple APKs per ABI.
            isEnable = true

            // Resets the list of ABIs for Gradle to create APKs for to none.
            reset()

            // Specifies a list of ABIs for Gradle to create APKs for.
            include("x86", "x86_64", "arm64-v8a", "armeabi-v7a")

            isUniversalApk = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            renderscriptOptimLevel = 3
            signingConfig = signingConfigs.getByName("release")
            multiDexEnabled = true
            isDebuggable = false
            isJniDebuggable = false
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            versionNameSuffix = "-debug"
            isJniDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            multiDexEnabled = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("type")
    productFlavors {
        create("default") {
            dimension = "type"
            // current timestamp of build
            buildConfigField("long", "BUILD_TIME", "$timestamp")
            // debug http requests. do not set this to true if you care about performance!!!!!
            if (System.getenv("CI") != null) {
                buildConfigField("boolean", "DEBUG_HTTP", "true")
            } else {
                buildConfigField("boolean", "DEBUG_HTTP", "false")
            }
            // Latest commit hash as BuildConfig.COMMIT_HASH
            buildConfigField("String", "COMMIT_HASH", "\"$gitCommitHash\"")
            // Get the current branch name as BuildConfig.BRANCH_NAME
            buildConfigField("String", "BRANCH_NAME", "\"$gitBranch\"")
            // Get remote url as BuildConfig.REMOTE_URL
            buildConfigField("String", "REMOTE_URL", "\"$gitRemote\"")
            buildConfigField("boolean", "ENABLE_AUTO_UPDATER", "true")
            buildConfigField("boolean", "DEFAULT_ENABLE_CRASH_REPORTING", "true")
            buildConfigField("boolean", "DEFAULT_ENABLE_CRASH_REPORTING_PII", "true")
            buildConfigField("boolean", "DEFAULT_ENABLE_ANALYTICS", "true")
            buildConfigField("boolean", "ENABLE_PROTECTION", "true")
            // Get the androidacy client ID from the androidacy.properties

            val propertiesA = Properties()
            val default = "5KYccdYxWB2RxMq5FTbkWisXi2dS6yFN9R7RVlFCG98FRdz6Mf5ojY2fyJCUlXJZ"
            if (project.rootProject.file("androidacy.properties").exists()) {
                propertiesA.load(project.rootProject.file("androidacy.properties").reader())
                propertiesA.setProperty(
                    "client_id", propertiesA.getProperty(
                        "client_id",
                        default
                    )
                )
            } else {
                propertiesA.setProperty("client_id", default)
            }

            buildConfigField(
                "String", "ANDROIDACY_CLIENT_ID", "\"" + propertiesA.getProperty("client_id") + "\""
            )

            buildConfigField(
                "java.util.List<String>",
                "ENABLED_REPOS",
                "java.util.Arrays.asList(\"androidacy_repo\")",
            )

        }

        // play variant. pretty similiar to default, but with an empty inital online repo list, and use play_client_id instead of client_id
        create("play") {
            dimension = "type"
            versionNameSuffix = "-play"
            applicationId = "com.androidacy.mmm"
            // current timestamp of build
            buildConfigField("long", "BUILD_TIME", "$timestamp")
            // debug http requests. do not set this to true if you care about performance!!!!!
            // if env var CI is set to true, this will be true
            if (System.getenv("CI") != null) {
                buildConfigField("boolean", "DEBUG_HTTP", "true")
            } else {
                buildConfigField("boolean", "DEBUG_HTTP", "false")
            }
            // Latest commit hash as BuildConfig.COMMIT_HASH
            buildConfigField("String", "COMMIT_HASH", "\"$gitCommitHash\"")
            // Get the current branch name as BuildConfig.BRANCH_NAME
            buildConfigField("String", "BRANCH_NAME", "\"$gitBranch\"")
            // Get remote url as BuildConfig.REMOTE_URL
            buildConfigField("String", "REMOTE_URL", "\"$gitRemote\"")
            buildConfigField("boolean", "ENABLE_AUTO_UPDATER", "false")
            buildConfigField("boolean", "DEFAULT_ENABLE_CRASH_REPORTING", "true")
            buildConfigField("boolean", "DEFAULT_ENABLE_CRASH_REPORTING_PII", "true")
            buildConfigField("boolean", "DEFAULT_ENABLE_ANALYTICS", "true")
            buildConfigField("boolean", "ENABLE_PROTECTION", "true")
            // Get the androidacy client ID from the androidacy.properties

            val propertiesA = Properties()
            val default = "5KYccdYxWB2RxMq5FTbkWisXi2dS6yFN9R7RVlFCG98FRdz6Mf5ojY2fyJCUlXJZ"
            if (project.rootProject.file("androidacy.properties").exists()) {
                propertiesA.load(project.rootProject.file("androidacy.properties").reader())
                propertiesA.setProperty(
                    "client_id", propertiesA.getProperty(
                        "client_id",
                        default
                    )
                )
            } else {
                propertiesA.setProperty("client_id", "\"" + default + "\"")
            }
            buildConfigField(
                "String", "ANDROIDACY_CLIENT_ID", "\"" + propertiesA.getProperty("client_id") + "\""
            )

            buildConfigField(
                "java.util.List<String>",
                "ENABLED_REPOS",
                "java.util.Arrays.asList(\"\")",
            )

        }

        create("fdroid") {
            dimension = "type"
            applicationIdSuffix = ".fdroid"
            // current timestamp of build
            buildConfigField("long", "BUILD_TIME", "$timestamp")
            // debug http requests. do not set this to true if you care about performance!!!!!
            if (System.getenv("CI") != null) {
                buildConfigField("boolean", "DEBUG_HTTP", "true")
            } else {
                buildConfigField("boolean", "DEBUG_HTTP", "false")
            }
            // Latest commit hash as BuildConfig.COMMIT_HASH
            buildConfigField("String", "COMMIT_HASH", "\"$gitCommitHash\"")
            // Get the current branch name as BuildConfig.BRANCH_NAME
            buildConfigField("String", "BRANCH_NAME", "\"$gitBranch\"")
            // Get remote url as BuildConfig.REMOTE_URL
            buildConfigField("String", "REMOTE_URL", "\"$gitRemote\"")

            // Need to disable auto-updater for F-Droid flavor because their inclusion policy
            // forbids downloading blobs from third-party websites (and F-Droid APK isn"t signed
            // with our keys, so the APK wouldn"t install anyways).
            buildConfigField("boolean", "ENABLE_AUTO_UPDATER", "false")

            // Disable crash reporting for F-Droid flavor by default
            buildConfigField("boolean", "DEFAULT_ENABLE_CRASH_REPORTING", "false")
            buildConfigField("boolean", "DEFAULT_ENABLE_CRASH_REPORTING_PII", "false")
            buildConfigField("boolean", "DEFAULT_ENABLE_ANALYTICS", "false")
            buildConfigField("boolean", "ENABLE_PROTECTION", "true")

            // Repo with ads or tracking feature are disabled by default for the
            // F-Droid flavor. at the same time, the alt repo isn"t particularly trustworthy
            buildConfigField(
                "java.util.List<String>",
                "ENABLED_REPOS",
                "java.util.Arrays.asList(\"\")",
            )

            // Get the androidacy client ID from the androidacy.properties
            val propertiesA = Properties()
            // If androidacy.properties doesn"t exist, use the fdroid client ID which is limited
            // to 50 requests per minute
            if (project.rootProject.file("androidacy.properties").exists()) {
                propertiesA.load(project.rootProject.file("androidacy.properties").inputStream())
            } else {
                propertiesA.setProperty(
                    "client_id", "dQ1p7X8bF14PVJ7wAU6ORVjPB2IeTinsuAZ8Uos6tQiyUdUyIjSyZSmN54QBbaTy"
                )
            }
            buildConfigField(
                "String", "ANDROIDACY_CLIENT_ID", "\"" + propertiesA.getProperty(
                    "client_id", "dQ1p7X8bF14PVJ7wAU6ORVjPB2IeTinsuAZ8Uos6tQiyUdUyIjSyZSmN54QBbaTy"
                ) + "\""
            )
            versionNameSuffix = "-fdroid"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable.add("MissingTranslation")
    }
}

val abiCodes = mapOf("armeabi-v7a" to 1, "x86" to 2, "x86_64" to 3, "arm64-v8a" to 4)

// For per-density APKs, create a similar map:
// val densityCodes = mapOf("mdpi" to 1, "hdpi" to 2, "xhdpi" to 3)


// For each APK output variant, override versionCode with a combination of
// abiCodes * 1000 + variant.versionCode. In this example, variant.versionCode
// is equal to defaultConfig.versionCode. If you configure product flavors that
// define their own versionCode, variant.versionCode uses that value instead.
androidComponents {
    onVariants { variant ->

        // Assigns a different version code for each output APK
        // other than the universal APK.
        variant.outputs.forEach { output ->
            val name = output.filters.find { it.filterType == ABI }?.identifier

            // Stores the value of abiCodes that is associated with the ABI for this variant.
            val baseAbiCode = abiCodes[name]
            // Because abiCodes.get() returns null for ABIs that are not mapped by ext.abiCodes,
            // the following code doesn't override the version code for universal APKs.
            // However, because you want universal APKs to have the lowest version code,
            // this outcome is desirable.
            if (baseAbiCode != null) {
                // Assigns the new version code to output.versionCode, which changes the version code
                // for only the output APK, not for the variant itself.
                val versioCode = output.versionCode.get() as Int
                output.versionCode.set((baseAbiCode * 1000) + versioCode)
            }
        }
    }
}

aboutLibraries {
    // Specify the additional licenses
    additionalLicenses = arrayOf("LGPL_3_0_only", "Apache_2_0")
}

configurations {
    // Access all imported libraries
    all {
        // Exclude all libraries with the following group and module
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
}

dependencies {
    // UI
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.emoji2:emoji2:1.4.0")
    implementation("androidx.emoji2:emoji2-views-helper:1.4.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("com.mikepenz:aboutlibraries:11.2.2")

    // Utils
    implementation("androidx.work:work-runtime:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.0.0-alpha.14")
    // logging interceptor
    debugImplementation("com.squareup.okhttp3:logging-interceptor:5.0.0-alpha.14")
    // Chromium cronet from androidacy
    implementation("org.chromium.net:cronet-embedded:119.6045.31")

    val libsuVersion = "5.2.2"
    // The core module that provides APIs to a shell
    implementation("com.github.topjohnwu.libsu:core:${libsuVersion}")

    // Optional: APIs for creating root services. Depends on ":core"
    implementation("com.github.topjohnwu.libsu:service:${libsuVersion}")

    // Optional: Provides remote file system support
    implementation("com.github.topjohnwu.libsu:io:${libsuVersion}")

    implementation("com.github.Fox2Code:RosettaX:1.0.9")
    implementation("com.github.Fox2Code:AndroidANSI:1.2.1")

    // Markdown
    // TODO: switch to an updated implementation
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2")
    implementation("com.google.net.cronet:cronet-okhttp:0.1.0")
    implementation("com.caverock:androidsvg:1.4")

    implementation("androidx.core:core-ktx:1.13.1")

    // timber
    implementation("com.jakewharton.timber:timber:5.0.1")

    // encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // some utils
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.apache.commons:commons-compress:1.26.1")

    // analytics
    implementation("ly.count.android:sdk:24.7.4")

    // annotations
    implementation("org.jetbrains:annotations-java5:24.1.0")

    // debugging
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // yes
    implementation("com.github.fingerprintjs:fingerprint-android:2.1.0")

    // room
    implementation("androidx.room:room-runtime:2.6.1")

    // To use Kotlin Symbol Processing (KSP)
    ksp("androidx.room:room-compiler:2.6.1")

    // optional - Kotlin Extensions and Coroutines support for Room
    implementation("androidx.room:room-ktx:2.6.1")

    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.28")

    // crash activity
    implementation("cat.ereza:customactivityoncrash:2.4.0")
}

android {
    sourceSets {
        this.getByName("main") {
            this.java.srcDir("src/main/kotlin")
        }
    }

    ndkVersion = "26.1.10909125"
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    //noinspection GrDeprecatedAPIUsage
    buildToolsVersion = "34.0.0"
    kotlinOptions {
        jvmTarget = "17"
    }
    @Suppress("DEPRECATION") packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
