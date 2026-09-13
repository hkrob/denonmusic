pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "denonmusic"

// Pure-JVM modules: no Android SDK required. The entire protocol layer lives here
// so it can be compiled and tested on any machine with a JDK, including CI runners
// and dev boxes without an Android SDK installed.
include(":core:heos")
include(":core:avr")
include(":core:smb")

// Android modules need an SDK. Including them unconditionally makes `./gradlew
// :core:heos:test` fail at configuration time on a machine without one, which would
// defeat the point of keeping the protocol layer Android-free. Gate them instead.
val hasAndroidSdk =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").exists()

if (hasAndroidSdk) {
    include(":app")
    include(":core:data")
    // include(":feature:probe")
} else {
    logger.lifecycle("No Android SDK detected - configuring JVM modules only.")
}
