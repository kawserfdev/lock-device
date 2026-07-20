plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

dependencies {
    // NotificationCompat + ServiceInfo helpers used by AppLockForegroundService.
    implementation("androidx.core:core-ktx:1.13.1")

    // WorkManager — used by LocationEnforcementWorker to periodically re-apply
    // the forced location-on setting (Android has no user-restriction that
    // locks location ON, so the user can still toggle it off in Settings; the
    // worker re-enables it within ~15 minutes).
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Material Components — used by the Location Compliance Activity layout
    // (MaterialCardView, MaterialButton) and the Material 3 theme attributes
    // (?attr/colorError, ?attr/textAppearance*). Brings in the transitive
    // dependency on the AppCompat theme used by @style/NormalTheme.
    implementation("com.google.android.material:material:1.12.0")

    // Activity — provides ComponentActivity + OnBackPressedDispatcher used by
    // LocationComplianceActivity for the non-dismissible back-press handling
    // (Android 13+ predictive-back compatible).
    implementation("androidx.activity:activity-ktx:1.9.0")
}

android {
    namespace = "com.example.app_lock"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.app_lock"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}
