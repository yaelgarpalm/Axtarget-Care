plugins { id("com.android.application") }

val supabaseValues = mutableMapOf<String, String>()
rootProject.file(".env.local").takeIf { it.isFile }?.forEachLine { line ->
    val entry = line.trim()
    if (entry.isNotEmpty() && !entry.startsWith("#")) {
        val separator = entry.indexOf('=')
        if (separator > 0) supabaseValues[entry.substring(0, separator).trim()] = entry.substring(separator + 1).trim().trim('"', '\'')
    }
}
val auditBuild = providers.gradleProperty("tzindaAudit").orNull == "true"
fun configValue(name: String): String = if (auditBuild) "" else System.getenv(name)?.takeIf { it.isNotBlank() } ?: supabaseValues[name].orEmpty()
fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.guardian_iot"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.guardian.tzindaapp.v2"
        if (auditBuild) applicationIdSuffix = ".audit"
        minSdk = 24
        targetSdk = 36
        versionCode = 14
        versionName = "2.1.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", buildConfigString(configValue("TZINDA_SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(configValue("TZINDA_SUPABASE_PUBLISHABLE_KEY")))
        buildConfigField("String", "SUPABASE_REDIRECT_URL", buildConfigString(configValue("TZINDA_SUPABASE_REDIRECT_URL").ifBlank { "com.guardian.tzindaapp.v2://auth-callback" }))
    }
    buildFeatures { buildConfig = true
        mlModelBinding = true
    }
    androidResources {
        noCompress += listOf("tflite", "task")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.4"))
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-cio:3.3.0")
    implementation("io.ktor:ktor-client-android:3.3.0")
    // Supabase Realtime logs through SLF4J; provide a no-op binding instead of noisy warnings.
    implementation("org.slf4j:slf4j-nop:2.0.16")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Lectura del texto de la caja o receta en el propio teléfono: el modelo va incluido en la app
    // y la imagen nunca sale del dispositivo.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    testImplementation("junit:junit:4.13.2")
    // The Android org.json stubs throw on JVM tests; this real implementation covers the parser.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
