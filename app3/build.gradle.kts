plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

abstract class PrepareChanchanhi3 : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val legacy: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val presentation: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val presentationResources: DirectoryProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:OutputDirectory abstract val generatedResources: DirectoryProperty
    @get:javax.inject.Inject abstract val operations: FileSystemOperations
    @TaskAction fun prepare() {
        operations.sync {
            into(destination)
            from(legacy) {
                exclude("**/MainActivity.kt", "**/AssistantSetupActivity.kt",
                    "**/AplVoiceInteractionSession.kt", "**/AplVoiceInteractionService.kt",
                    "**/HighlightOverlayManager.kt", "**/GuideTextOverlayManager.kt",
                    "**/GuideFeedbackOverlayManager.kt", "**/GuideController.kt", "**/GuideCaption.kt",
                    "**/AssistAccessibilityService.kt", "**/GuideStateRepository.kt")
            }
            from(presentation) { exclude("**/Apl2GuideController.kt") }
        }
        operations.sync {
            from(presentationResources) { exclude("values/strings.xml") }
            into(generatedResources)
        }
    }
}
val prepareChanchanhi3 by tasks.registering(PrepareChanchanhi3::class) {
    legacy.set(layout.projectDirectory.dir("../app/src/main/java"))
    presentation.set(layout.projectDirectory.dir("../app2/src/main/java"))
    presentationResources.set(layout.projectDirectory.dir("../app2/src/main/res"))
    destination.set(layout.buildDirectory.dir("generated/presentation"))
    generatedResources.set(layout.buildDirectory.dir("generated/shared-resources"))
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin!!.addGeneratedSourceDirectory(prepareChanchanhi3, PrepareChanchanhi3::destination)
    variant.sources.res!!.addGeneratedSourceDirectory(prepareChanchanhi3, PrepareChanchanhi3::generatedResources)
}
android {
    namespace = "com.example.aplassistmvp0"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ccomet.apl3"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "3.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main") {
        manifest.srcFile("../app2/src/main/AndroidManifest.xml")
        assets.srcDir("../app2/src/main/assets")
    }
    sourceSets.getByName("androidTest") { assets.srcDir("../subscription-bridge/test/fixtures") }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    buildFeatures { compose=true;buildConfig=false }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
