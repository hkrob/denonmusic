import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM, like :core:heos and :core:avr: jcifs-ng is a plain Java SMB client with no Android
// dependency, and the header parsers operate on InputStream alone, so none of this needs an
// Android SDK to build or test.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jcifs.ng)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
