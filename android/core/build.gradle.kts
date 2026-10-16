plugins { kotlin("jvm"); kotlin("plugin.serialization") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
tasks.withType<Test>().configureEach {
    systemProperty("samplesDir", rootProject.file("samples").absolutePath)
    inputs.dir(rootProject.file("samples"))
}
