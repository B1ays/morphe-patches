import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    compileOnly(libs.kotlin.stdlib)
}

configure<LibraryExtension> {
    namespace = "app.morphe.extension"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }
}
