import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials, kept out of the repo entirely — the file is gitignored and the
// keystore it points at lives outside the working tree. See keystore.properties.template.
//
// A properties file rather than environment variables because Android Studio's Build menu does not
// inherit a shell's exported vars, so a GUI release build would fail in a way that looks like a
// broken config rather than a missing export.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("app/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// `~` is not expanded by File, and the documented location for the keystore starts with one.
val releaseStoreFile: File? = keystoreProperties.getProperty("storeFile")
    ?.replaceFirst(Regex("^~"), System.getProperty("user.home"))
    ?.let { File(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.vynex.gumshoos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vynex.gumshoos"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Null when app/keystore.properties is missing or points at a keystore that isn't
            // there, which yields the unsigned APK this project produced before signing existed.
            // Degrading rather than failing keeps a fresh clone buildable; the warning below is what
            // stops that being silent, since an unsigned APK looks identical until install refuses.
            signingConfig = signingConfigs.findByName("release")
            if (signingConfig == null) {
                logger.warn(
                    "Gumshoos: no release signing config — app/keystore.properties is missing or " +
                        "its storeFile does not exist. assembleRelease will emit an UNSIGNED apk " +
                        "that cannot be installed. See keystore.properties.template."
                )
            }

            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            // So a release build can sit on the phone while a debug one is tested beside it. The
            // manifest's `.SearchShortcut` and `.AssistTrigger` aliases expand against `namespace`
            // rather than applicationId, and both are addressed as
            // ComponentName(context.packageName, <absolute class name>), so the suffix does not
            // break them. Nothing else in the app hardcodes its own applicationId.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"    // 1.8?
    }
    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which gates the event trace out of a release build. AGP 8 stopped
        // generating BuildConfig unless it is asked for.
        buildConfig = true
    }
    // No composeOptions block: the Kotlin 2.0 Compose compiler plugin supplies its own version and
    // kotlinCompilerExtensionVersion is ignored.
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.reorderable)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
// WidgetLayoutTest reads the widget layouts off disk to check they only use view classes
// RemoteViews will inflate. Gradle has no way to know that, so without this the test is cached as
// up-to-date and skipped on exactly the change it exists to catch — editing a layout doesn't touch
// the test classpath.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res/layout")
        .withPropertyName("widgetLayouts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
