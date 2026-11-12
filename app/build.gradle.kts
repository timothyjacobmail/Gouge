import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Build one small RustCrypto component for every Android ABI; no precompiled blobs in Git.
val rustRoot = rootProject.layout.projectDirectory.dir("native/password-kdf")
val nativeOutput = layout.buildDirectory.dir("generated/rustJniLibs")
val rustBuild = rootProject.layout.buildDirectory.dir("rust-password-kdf")
val windows = System.getProperty("os.name").startsWith("Windows")
val executableSuffix = if (windows) ".exe" else ""
val localCargo = rootProject.file("build/toolchains/cargo/bin/cargo$executableSuffix")
val cargo = providers.environmentVariable("GOUGE_CARGO").orNull
    ?: if (localCargo.isFile) localCargo.absolutePath else "cargo"
val sdkProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val sdkRoot = providers.environmentVariable("ANDROID_HOME").orNull ?: sdkProperties.getProperty("sdk.dir")
val ndk = providers.environmentVariable("ANDROID_NDK_HOME").orNull?.let(::file)
    ?: rootProject.file("build/toolchains/android-ndk-r30").takeIf { it.isDirectory }
    ?: file("$sdkRoot/ndk/30.0.16248370")
val host = if (windows) "windows-x86_64" else if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
val rustAbis = mapOf(
    "arm64-v8a" to ("aarch64-linux-android" to "aarch64-linux-android"),
    "armeabi-v7a" to ("armv7-linux-androideabi" to "armv7a-linux-androideabi"),
    "x86_64" to ("x86_64-linux-android" to "x86_64-linux-android"),
    "x86" to ("i686-linux-android" to "i686-linux-android")
)
val nativeBuilds = rustAbis.map { (abi, target) ->
    tasks.register<Exec>("buildPasswordKdf" + abi.replace("-", "_").replaceFirstChar { it.uppercase() }) {
        inputs.files(fileTree(rustRoot) { exclude("target/**") })
        inputs.property("ndk", ndk.absolutePath)
        inputs.property("target", target.first)
        outputs.file(rustBuild.map { it.file("${target.first}/release/libgouge_password_kdf.so") })
        workingDir(rustRoot)
        environment("CARGO_TARGET_DIR", rustBuild.get().asFile.absolutePath)
        if (localCargo.isFile && cargo == localCargo.absolutePath) {
            environment("CARGO_HOME", rootProject.file("build/toolchains/cargo").absolutePath)
            environment("RUSTUP_HOME", rootProject.file("build/toolchains/rustup").absolutePath)
        }
        val linker = ndk.resolve("toolchains/llvm/prebuilt/$host/bin/${target.second}30-clang" + if (windows) ".cmd" else "")
        environment("CARGO_TARGET_${target.first.uppercase().replace('-', '_')}_LINKER", linker.absolutePath)
        environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
        commandLine(cargo, "build", "--locked", "--release", "--target", target.first, "--manifest-path", rustRoot.file("Cargo.toml").asFile)
        doFirst { check(linker.isFile) { "Install Android NDK 30.0.16248370 or set ANDROID_NDK_HOME; see docs/performance.md" } }
    }
}
val packagePasswordKdf by tasks.registering(Sync::class) {
    dependsOn(nativeBuilds)
    into(nativeOutput)
    rustAbis.forEach { (abi, target) ->
        from(rustBuild.map { it.file("${target.first}/release/libgouge_password_kdf.so") }) { into(abi) }
    }
}

android {
    namespace = "com.example.installlock"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.installlock"
        minSdk = 30
        targetSdk = 36
        versionCode = 23
        versionName = "1.10.0-beta.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.getByName("main").jniLibs.directories.add(nativeOutput.get().asFile.absolutePath)
}

tasks.named("preBuild") { dependsOn(packagePasswordKdf) }

dependencies {
    implementation(project(":secretvault"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
