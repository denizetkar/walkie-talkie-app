import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
}

val jnaAar by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

configure<ApplicationExtension> {
    namespace = "com.denizetkar.walkietalkieapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.denizetkar.walkietalkieapp"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            // This tells Gradle: "Only package libraries for 64-bit ARM"
            // It will strip out the x86 and armv7 libs that JNA added.
            abiFilters.add("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    lint {
        disable.add("ForegroundServiceType")
        disable.add("ForegroundServicePermission")
    }

    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_19
        targetCompatibility = JavaVersion.VERSION_19
    }
    packaging {
        jniLibs {
            pickFirsts.add("lib/**/libc++_shared.so")
            pickFirsts.add("lib/**/libjnidispatch.so")
            pickFirsts.add("lib/**/libwalkie_talkie_engine.so")
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_19)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)

    implementation(libs.jna)
    jnaAar(libs.jna) {
        artifact {
            name = "jna"
            type = "aar"
            extension = "aar"
        }
    }
}

// ===========================================================================
// TASK 1: Build Rust
// ===========================================================================
val buildRust = tasks.register<Exec>("buildRust") {
    group = "rust"
    description = "Builds the Rust library for Android"
    outputs.upToDateWhen { false }
    workingDir = file("../rust")
    val script = file("../rust/build.ps1")
    commandLine("pwsh", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
    doFirst { println(">>> STARTING RUST BUILD <<<") }
}

// ===========================================================================
// TASK 2: Extract JNA (Updated for JNA 5.18.1 Structure)
// ===========================================================================
val extractJna = tasks.register("extractJna") {
    group = "rust"
    description = "Extracts libjnidispatch.so from the JNA AAR"
    outputs.upToDateWhen { false }

    inputs.files(jnaAar)
    val outputDir = file("src/main/jniLibs")
    outputs.dir(outputDir)

    doLast {
        println(">>> STARTING JNA EXTRACTION <<<")

        val jnaFile = jnaAar.singleFile
        println("Target AAR: ${jnaFile.name}")

        // 1. Unzip to temp
        val tempDir = layout.buildDirectory.dir("tmp/jna_unzip").get().asFile
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        copy {
            from(zipTree(jnaFile))
            into(tempDir)
        }

        // 2. Copy specific architectures
        // JNA 5.18.1 uses standard names: jni/arm64-v8a/libjnidispatch.so
        val archs = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        var foundAny = false

        for (arch in archs) {
            val source = File(tempDir, "jni/$arch/libjnidispatch.so")
            val dest = File(outputDir, "$arch/libjnidispatch.so")

            if (source.exists()) {
                dest.parentFile.mkdirs()
                source.copyTo(dest, overwrite = true)
                println("SUCCESS: Extracted $arch")
                foundAny = true
            }
        }

        if (!foundAny) {
            println("ERROR: No native libraries found in AAR!")
            println("Listing extracted 'jni' contents:")
            File(tempDir, "jni").walk().forEach { println(" - ${it.relativeTo(tempDir)}") }
            throw GradleException("JNA Extraction Failed.")
        }

        println(">>> JNA EXTRACTION FINISHED <<<")
    }
}

// ===========================================================================
// HOOKS
// ===========================================================================
tasks.named("preBuild").configure {
    dependsOn(buildRust)
    dependsOn(extractJna)
}