import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "me.lakitu.kqprobe"
    compileSdk = 36
    // prebuilt native deps (libgfxstream_backend.so, libkqserver.so) are produced
    // by ../scripts/build-native.sh + CI (see README); gradle only links the JNI
    // probe against them via cpp/CMakeLists.txt search path.
    // They are gitignored binaries; drop them into app/src/main/jniLibs/arm64-v8a/.

    defaultConfig {
        applicationId = "me.lakitu.kqprobe"
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // Optional CI/dev signing: create keystore.properties (storeFile/storePassword/
            // keyAlias/keyPassword) or env KQPROBE_KEYBASE64 (base64 keystore) + KQPROBE_KSPASS
            val ksProps = rootProject.file("keystore.properties")
            val envB64 = System.getenv("KQPROBE_KEYBASE64")
            if (ksProps.exists()) {
                val p = Properties().apply { File(ksProps.path).inputStream().use { load(it) } }
                signingConfigs.create("release") {
                    storeFile = file(p.getProperty("storeFile"))
                    storePassword = p.getProperty("storePassword")
                    keyAlias = p.getProperty("keyAlias")
                    keyPassword = p.getProperty("keyPassword")
                }
                signingConfig = signingConfigs.getByName("release")
            } else if (!envB64.isNullOrBlank()) {
                val ks = File.createTempFile("kq", ".jks")
                ks.deleteOnExit()
                ks.writeBytes(Base64.getDecoder().decode(envB64.trim()))
                signingConfigs.create("release") {
                    storeFile = ks
                    storePassword = System.getenv("KQPROBE_KSPASS")
                    keyAlias = "kqprobe"
                    keyPassword = System.getenv("KQPROBE_KSPASS")
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    lint { abortOnError = false }
}
