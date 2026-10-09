plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "io.github.ponpokoo.mastodonclient.releasechecks"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        providers.gradleProperty("nagisa.releaseTestServer").orNull?.let {
            testInstrumentationRunnerArguments["serverDomain"] = it
        }
    }

    targetProjectPath = ":app"
    // Run the runner in its own process so R8 cannot remove its dependencies from the app.
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("release") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "release"
    }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
