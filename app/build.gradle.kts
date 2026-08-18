import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val productionVersionCode = 34
val productionVersionName = "1.0.0-rc2"
val diagnosticsUploadUrl = keystoreProperties.getProperty("diagnosticsUploadUrl").orEmpty()
val diagnosticsUploadPassword = keystoreProperties.getProperty("diagnosticsUploadPassword").orEmpty()

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r") + "\""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.audiobookshelf.aaos"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.shelfdrive.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = productionVersionCode
        versionName = productionVersionName
        buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "false")
        buildConfigField("String", "DIAGNOSTICS_UPLOAD_URL", buildConfigString(""))
        buildConfigField("String", "DIAGNOSTICS_UPLOAD_PASSWORD", buildConfigString(""))

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "diagnostics"

    productFlavors {
        create("prod") {
            dimension = "diagnostics"
        }
        create("diagnostics") {
            dimension = "diagnostics"
            versionCode = productionVersionCode + 1
            versionNameSuffix = "-diagnostics"
            buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "true")
            buildConfigField("String", "DIAGNOSTICS_UPLOAD_URL", buildConfigString(diagnosticsUploadUrl))
            buildConfigField("String", "DIAGNOSTICS_UPLOAD_PASSWORD", buildConfigString(diagnosticsUploadPassword))
        }
    }

    signingConfigs {
        create("release") {
            val configuredStoreFile = keystoreProperties.getProperty("storeFile")
            if (!configuredStoreFile.isNullOrBlank()) {
                storeFile = rootProject.file(configuredStoreFile)
            }
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val validateDiagnosticsUploadConfig = tasks.register("validateDiagnosticsUploadConfig") {
    doLast {
        check(diagnosticsUploadUrl.isNotBlank()) { "diagnosticsUploadUrl is missing from keystore.properties." }
        check(diagnosticsUploadPassword.isNotBlank()) { "diagnosticsUploadPassword is missing from keystore.properties." }
    }
}

tasks.matching { it.name == "bundleDiagnosticsRelease" }.configureEach {
    dependsOn(validateDiagnosticsUploadConfig)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
