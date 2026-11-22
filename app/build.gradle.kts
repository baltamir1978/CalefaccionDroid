import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Credenciales de Netatmo Connect para arrancar: en local.properties (fuera del repositorio).
// También se pueden meter en Ajustes dentro de la app, como en iOS.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localString(key: String) = "\"" + (local.getProperty(key) ?: "").replace("\"", "") + "\""

android {
    namespace = "es.altamirano.calefaccion"
    compileSdk = 37

    defaultConfig {
        applicationId = "es.altamirano.calefaccion"
        minSdk = 31          // Android 12
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.3"
        buildConfigField("String", "NETATMO_CLIENT_ID", localString("netatmo.clientId"))
        buildConfigField("String", "NETATMO_CLIENT_SECRET", localString("netatmo.clientSecret"))
    }

    // La clave de release vive fuera del repositorio; sin ella la release sale sin firmar.
    val keyFile = File(System.getProperty("user.home"), ".android/claves/calefaccion-release.properties")
    val releaseKey = if (keyFile.exists()) Properties().apply { keyFile.inputStream().use { load(it) } } else null
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = File(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    // Leer `Locale.getDefault()` al pintar no se entera de un cambio de idioma en caliente; aquí da
    // igual, porque al cambiar el idioma Android recrea la actividad. Aviso, no error.
    lint { warning += "NonObservableLocale" }

    buildTypes {
        release {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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
}

dependencies {
    implementation("es.altamirano:common")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.browser)
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
}
