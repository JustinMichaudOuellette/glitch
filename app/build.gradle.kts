plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "ca.justinmo.glitch"
  compileSdk {
    version = release(37)
  }

  defaultConfig {
    applicationId = "ca.justinmo.glitch"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      // The release build is the one that gets shrunk. Off, this dexed the whole of
      // material-icons-extended - thousands of ImageVector icons of which the app uses
      // about fifteen - and kept every unused string and drawable: a release that is
      // several times the size it needs to be. On, AGP 9 puts R8's code shrinking and
      // obfuscation and resource shrinking behind this one switch.
      //
      // The default keep rules ride along (KeepRules.includeDefault). They are load
      // bearing here: the codec is bound by name, Java_ca_justinmo_glitch_jpeg_JpegNative_*
      // in jpeg_pipe.c, and the default native-method keep rule is what stops R8 renaming
      // JpegNative or its methods out from under those symbols.
      optimization {
        enable = true
      }
      // Only the release build is trimmed to ARM, the two ABIs that are phones. x86 and
      // x86_64 are emulators and a few Chromebooks, and libjpeg-turbo ships a copy of
      // itself per ABI - the native side is most of this APK - so carrying them doubled
      // the download for a device that can run neither. Debug keeps all four, so a build
      // on an x86_64 emulator still installs.
      ndk {
        abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
  }

  // Our own decoder and encoder, from libjpeg-turbo's source. The first configure
  // fetches and builds it for each ABI (see src/main/cpp/CMakeLists.txt), so
  // cloning this repository and building is the whole setup.
  ndkVersion = "28.2.13676358"
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  // No JPEG dependency here on purpose: it is built from source by CMake.
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  // The parity tests run on a device or emulator: the codec is an .so.
  androidTestImplementation(kotlin("test"))
}
