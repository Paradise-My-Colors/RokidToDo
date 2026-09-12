plugins { id("com.android.application") }

android {
    namespace = "com.paradisemc.nexus.plugin.todo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paradisemc.nexus.plugin.todo"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val sdkVersion = providers.gradleProperty("sdkVersion").orElse("sdk-v0.16.0")

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:${sdkVersion.get()}")
    testImplementation("junit:junit:4.13.2")
}
