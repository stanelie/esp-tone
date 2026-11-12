plugins {
    id("com.android.application")
}

android {
    namespace = "app.toneplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.toneplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")   // pull down to rescan
}
