plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint)
}

// One linter, applied from here rather than repeated in five module files. Rule configuration lives
// in .editorconfig, which is also what an IDE reads - so the editor and `./gradlew ktlintCheck`
// can't disagree about what this project's style is.
val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        // KSP and Room generate Kotlin into build/, and it is not ours to format.
        filter { exclude { it.file.path.contains("${File.separator}build${File.separator}") } }
    }
}
