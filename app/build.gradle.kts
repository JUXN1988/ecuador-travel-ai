import java.util.Properties
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// Firma de release: lee keystore.properties (creado por build_release.ps1 / .sh) o variables de entorno.
val keystoreProps = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String, env: String): String? = keystoreProps.getProperty(name) ?: System.getenv(env)
val releaseStorePath = prop("storeFile", "KEYSTORE_PATH") ?: "travesias-upload-key.jks"
// OJO: `file(...)` dentro del módulo :app resuelve lo relativo contra la carpeta app/, no
// contra la raíz. Como keystore.properties guarda "travesias-upload-key.jks" a secas, Gradle
// buscaba app/travesias-upload-key.jks, no lo encontraba, y firmaba el release con la clave
// DEBUG — que Play Store rechaza. `rootProject.file(...)` sí lo busca donde está.
val releaseStoreFile = rootProject.file(releaseStorePath)
val hasReleaseKeystore = releaseStoreFile.exists() && prop("storePassword", "STORE_PASSWORD") != null

android {
  namespace = "ec.travesias.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "ec.travesias.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 14
    versionName = "2.10.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      storeFile = releaseStoreFile
      storePassword = prop("storePassword", "STORE_PASSWORD") ?: ""
      keyAlias = prop("keyAlias", "KEY_ALIAS") ?: "upload"
      keyPassword = prop("keyPassword", "KEY_PASSWORD") ?: ""
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Sin keystore de release se firma con la clave debug para poder probar el APK; Play exige la de release.
      signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
    // APK de PRUEBAS: exactamente lo mismo que release, pero firmado con la clave DEBUG.
    // Por que existe: Android solo deja ACTUALIZAR una app si la firma coincide con la ya
    // instalada. Todo lo que se instalo hasta el 2026-09-09 salio firmado con la clave debug
    // (habia un fallo que impedia encontrar el keystore de verdad). Con esta variante se
    // instala encima sin desinstalar y sin perder rutas, fotos ni la cuenta.
    // El .aab y el APK de release siguen firmados con la clave de subida, que es la que pide Play.
    create("pruebas") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      versionNameSuffix = "-pruebas"
      isMinifyEnabled = false
      isShrinkResources = false
    }
    debug {
      versionNameSuffix = "-debug"
    }
  }

  bundle {
    language { enableSplit = false } // Incluir todos los idiomas en el AAB
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // Fragment 1.3.0+ es obligatorio si se usa registerForActivityResult / rememberLauncherForActivityResult
  // (lint InvalidFragmentVersionForActivityResult). Se fija explícito porque nada más del proyecto
  // trae una versión moderna de fragment de forma transitiva.
  implementation(libs.androidx.fragment.ktx)
  // Huella / cara / patrón / PIN para recuperar la contraseña sin tener que acordarse de ella.
  // Necesita que MainActivity sea FragmentActivity (BiometricPrompt lo exige).
  implementation(libs.androidx.biometric)
  // EXIF de AndroidX: lee giro y coordenadas de CUALQUIER foto (incluida HEIC y las que
  // llegan como content://). La del framework se atraganta con esos streams y por eso
  // las fotos salían giradas.
  implementation(libs.androidx.exifinterface)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.osmdroid.android)
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)            // sin uso en el codigo
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // Atajo "Continuar con Google" al registrarse: solo trae nombre, correo y foto para
  // rellenar el formulario. La identidad sigue siendo la llave del viajero (ver CuentaGoogle.kt).
  // El botón se enciende solo cuando google-services.json traiga un oauth_client.
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)  // sin uso en el codigo
  // implementation(libs.firebase.appcheck.debug)      // sin uso en el codigo
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // Vico (gráficas) se quedó sin usar cuando se retiró DashboardSection de la v1: fuera, pesaba ~1 MB.
  // implementation(libs.vico.compose)
  // implementation(libs.vico.compose.m3)
  // implementation(libs.vico.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
