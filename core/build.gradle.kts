// The Rust core: stegpony-ffi cross-compiled into src/main/jniLibs and its
// UniFFI Kotlin bindings in src/main/kotlin/uniffi, both produced by
// scripts/build-core.sh and never committed (see .gitignore).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stegpony.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Generated UniFFI code guards its java.lang.ref.Cleaner use with a
        // reflection check lint cannot follow; see lint.xml.
        lintConfig = file("lint.xml")
    }
}

// Runs scripts/build-core.sh when its outputs are missing (a fresh clone, or
// F-Droid's build), so `gradle` alone is enough. After changing StegPonyCore,
// run the script yourself; this task only fills in what is absent.
val cargoBinDir: String =
    (System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo") + "/bin"

val buildCore = tasks.register<Exec>("buildCore") {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-core.sh")
    val soFile = file("src/main/jniLibs/arm64-v8a/libstegpony_ffi.so")
    val bindings = file("src/main/kotlin/uniffi/stegpony_ffi/stegpony_ffi.kt")
    outputs.files(soFile, bindings)
    onlyIf { !soFile.exists() || !bindings.exists() }
    // Resolved at execution time, so configuring the project never needs the
    // SDK location.
    val ndkVersionProp = providers.gradleProperty("ndkVersion")
    doFirst {
        environment("PATH", "$cargoBinDir${File.pathSeparator}${System.getenv("PATH") ?: ""}")
        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("ANDROID_NDK")
            ?: run {
                val sdkDir = System.getenv("ANDROID_HOME")
                    ?: System.getenv("ANDROID_SDK_ROOT")
                    ?: android.sdkDirectory.absolutePath
                "$sdkDir/ndk/${ndkVersionProp.get()}"
            }
        environment("ANDROID_NDK_HOME", ndkHome)
    }
}

tasks.named("preBuild") { dependsOn(buildCore) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(buildCore)
}

dependencies {
    // The generated bindings load libstegpony_ffi.so through JNA; the @aar
    // artifact carries jnidispatch for each Android ABI.
    // api: public classes in the bindings reach JNA types, so the app compiles
    // against it too.
    api("net.java.dev.jna:jna:5.19.1@aar")
}
