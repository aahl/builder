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
        applicationId = "cn.alone.hamitv"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1-a1"
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

    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
