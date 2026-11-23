plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Only device/transport infrastructure is shared. No previous GuideEngine or plan code is compiled.
abstract class PrepareInfrastructure : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val source: DirectoryProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:javax.inject.Inject abstract val operations: FileSystemOperations
    @TaskAction fun prepare() { operations.sync {
    from(source) {
        include("**/accessibility/AccessibilityNodeModel.kt", "**/accessibility/AccessibilityTreeParser.kt",
            "**/accessibility/LocalInputRevisions.kt", "**/accessibility/PublicTimePickerRoles.kt",
            "**/accessibility/RecipientResultBinding.kt", "**/guide/BridgeConnectionConfig.kt",
            "**/guide/SubscriptionBridgeClient.kt", "**/guide/BridgeRequestFailure.kt",
            "**/assistant/SpeechProviderResolver.kt",
            "**/assistant/AplRecognitionService.kt")
    }
    into(destination)
    } }
}
val prepareInfrastructure by tasks.registering(PrepareInfrastructure::class) {
    source.set(layout.projectDirectory.dir("../app/src/main/java"))
    destination.set(layout.buildDirectory.dir("generated/infrastructure"))
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin!!.addGeneratedSourceDirectory(prepareInfrastructure, PrepareInfrastructure::destination)
}
android {
    namespace = "dev.ccomet.chanchanhi04"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ccomet.chanchanhi"
        minSdk = 26
        targetSdk = 36
        versionCode = 64
        versionName = "0.4.60-dev"
        buildConfigField("boolean", "COMPACT_GUIDANCE", "true")
        buildConfigField("boolean", "RESEARCH_BUILD", "true")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["sortProbeEnabled"] = providers.gradleProperty("sortProbe").orElse("false").get()
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir("../research-protocol")
    sourceSets.getByName("test").resources.srcDir("../research-protocol")
    sourceSets.getByName("test").resources.srcDir("../research-console/test/fixtures")
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.airbnb.android:lottie:6.7.1")
    // 휴대폰 로컬 개인정보 감지: 항목 추출(한국어 모델은 첫 사용 시 내려받음)과 한국어 OCR(앱에 포함)
    implementation("com.google.mlkit:entity-extraction:16.0.0-beta6")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
