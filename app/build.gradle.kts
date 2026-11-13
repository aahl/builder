plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.protobuf")
}

android {
    namespace = "cn.alone.hamitv"
    compileSdk = 34

    defaultConfig {
        // The security centre's allowlist (mitv_security_center.xml, printed at boot by
        // MitvConfigParser) is matched on the package name only, and it is what lets this
        // app be started at boot. com.ts.launcher is the allowlisted identity used by
        // CleanYourTV for the same purpose; override with -PappId=... if that name ever
        // stops working on a set.
        applicationId = (findProperty("appId") as String?) ?: "com.ts.launcher"
        minSdk = 28
        targetSdk = 34
        versionCode = 14
        versionName = "0.3.0-b1"
    }

    // Pin the debug signing key to an explicit file when CI provides one,
    // otherwise AGP silently generates a fresh key on every run and APKs
    // cannot be installed over each other.
    val pinnedDebugKey = System.getenv("HAMITV_DEBUG_KEYSTORE")?.let { file(it) }
    signingConfigs {
        if (pinnedDebugKey != null && pinnedDebugKey.exists()) {
            getByName("debug") {
                storeFile = pinnedDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/INDEX.LIST"
        resources.excludes += "/META-INF/io.netty.versions.properties"
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(project(":launcher"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")

    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // edge-tts is a websocket; the CIO client shares the ktor version the
    // server already uses, so this is one engine rather than a second stack.
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")

    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
