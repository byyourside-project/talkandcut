plugins { id("com.android.library") }
android {
    namespace = "com.jiangdg.uvccamera"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    sourceSets["main"].assets.srcDir("licenses")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.elvishew:xlog:1.11.0")
}
