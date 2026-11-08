import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 compiles Kotlin itself (built-in Kotlin), so only the Compose compiler plugin is applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. The key and its passwords live outside the project, in a properties file with
// storeFile (relative to that file, or absolute), storePassword, keyAlias and keyPassword.
// Default location: <home>/keys/grocery-sync-release.properties; set GROCERY_SYNC_SIGNING to use another file.
// Without that file, assembleRelease still works but produces an unsigned APK.
val releaseSigningFile: File = System.getenv("GROCERY_SYNC_SIGNING")?.takeIf { it.isNotBlank() }?.let(::File)
    ?: File(System.getProperty("user.home"), "keys/grocery-sync-release.properties")
val releaseSigning: Properties? = releaseSigningFile.takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}
if (releaseSigning == null && gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
    logger.warn("No release signing file at $releaseSigningFile: the release APK will be unsigned.")
}

android {
    namespace = "com.grocerysync.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.grocerysync.app"
        minSdk = 26
        targetSdk = 36
        // 2: amounts with units (database version 2). 3: search, swipe, unique names, hidden token and
        // "Added by" (database version 3). 4: search on the page shown, To buy too (database version 3).
        // 5: share the list, common items, Hindi / English names, paste a list and shop sections
        // (database version 3). 6: the new name (Kirana List) and look, the same data (database version 3).
        // 7: more Undo, Ticked today, Hindi, Simple mode and change notifications (database version 3).
        // Android refuses to install an older versionCode over this one, so an older app, which cannot
        // open a newer database, can't replace it.
        versionCode = 7
        // Shown to people (and at the bottom of Settings); raise it with every release (1.1 = units,
        // 1.2 = search, swipe, unique names, hidden token, "Added by" and required units, 1.3 = search
        // on To buy too: it searches the page shown, 1.4 = share the list, common items, Hindi / English
        // names, paste a list and To buy by shop section, 1.5 = Kirana List: the new name, icon and
        // "fresh market" colours, with a colour for each shop section, 1.6 = Undo after - / +, the Need
        // switch and Edit, Ticked today, the app in Hindi with a Language choice, Simple mode for elders
        // and a notification when someone else adds to To buy).
        versionName = "1.6"

        // UI tests (src/androidTest): run them on an emulator with tools/e2e/uitest.sh, not with
        // ./gradlew connectedDebugAndroidTest (that runs on every connected device). How they work: GroceryUiTest.kt.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                val store = File(releaseSigning.getProperty("storeFile").orEmpty())
                storeFile = if (store.isAbsolute) store else File(releaseSigningFile.parentFile, store.path)
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Same applicationId as release on purpose (no applicationIdSuffix).
            // Cleartext to local test servers is allowed by src/debug/res/xml/network_security_config.xml.
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // For testing only: the release build (same R8 shrinking and rules) that may also talk to the
        // local mock web app over plain HTTP (src/qa/res/xml/network_security_config.xml). Its own app ID
        // (com.grocerysync.app.qa), name ("Kirana QA") and the debug signing key mean it installs next
        // to the real app and can never replace it on a phone. Build it with ./gradlew assembleQa.
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // android.* stubs return defaults instead of throwing in JVM unit tests.
        unitTests.isReturnDefaultValues = true
        // UI tests run without window and transition animations (Compose animations follow the test clock).
        animationsDisabled = true
    }

    sourceSets {
        // The fake web app (FakeSheetServer) is shared: the JVM tests check it against the real SheetsClient
        // and SyncEngine, and the UI tests on a device sync with it.
        getByName("test").kotlin.directories += "src/sharedTest/java"
        getByName("androidTest").kotlin.directories += "src/sharedTest/java"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json ships with Android, but the android.jar stubs used by JVM unit tests are empty.
    testImplementation(libs.org.json)

    // UI tests: test APK only, except ui-test-manifest (debug builds only), which declares the empty
    // activity Compose tests can host content in. None of these reach the release (or qa) APK.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
