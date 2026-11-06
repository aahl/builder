// CleanYourTV's launcher, vendored verbatim from Gathin23/cleanyourtv (MIT) and
// built as a library so the one APK can be both the home screen and the ESPHome
// bridge. See third_party/cleanyourtv/UPSTREAM.md for the commit and the local
// patches; keep this file free of anything the app module should own
// (applicationId, version, signing) so it stays a plain library.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.gathin.tvhome"
    compileSdk = 34

    defaultConfig {
        // Raised from their 26 so the merged APK keeps our floor.
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    testImplementation("junit:junit:4.13.2")
}
