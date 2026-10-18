import java.net.URI

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}
val updateFeed = providers.gradleProperty("qingjiUpdateUrl").getOrElse("https://fyy-cn.github.io/qingji-releases/releases/latest.json")
val downloadPage = providers.gradleProperty("qingjiDownloadPage").getOrElse("https://fyy-cn.github.io/qingji-releases/")
val githubRepository = providers.gradleProperty("qingjiGithubRepository").getOrElse("fyy-cn/qingji-releases")
listOf(updateFeed, downloadPage).forEach { address ->
    val uri = URI(address)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "Update addresses must be HTTPS URLs" }
}
require(githubRepository.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+"))) { "Set a valid GitHub release repository" }
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = "cn.qingji.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.qingji.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "1.5.0"
        buildConfigField("String", "UPDATE_URL", quoted(updateFeed))
        buildConfigField("String", "DOWNLOAD_PAGE", quoted(downloadPage))
        buildConfigField("String", "GITHUB_REPOSITORY", quoted(githubRepository))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // Keep the installed v1 signing identity for this direct-download test channel.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.44.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    providers.gradleProperty("qingjiScreenshots").orNull?.let {
        systemProperty("qingji.screenshots", it)
        systemProperty("roborazzi.test.record", "true")
    }
}
