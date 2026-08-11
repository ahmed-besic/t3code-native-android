plugins {
  id("com.android.library")
}

android {
  namespace = "expo.modules.t3terminal"
  compileSdk = 37
  ndkVersion = "27.0.12077973"

  defaultConfig {
    minSdk = 26

    externalNativeBuild {
      cmake {
        cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
}
