plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/*
 * Native Rust Build Integration (`cargo-ndk`):
 * -------------------------------------------
 * `:core:adblock-rust` compiles Brave's `adblock` crate (`./rust/Cargo.toml`) into
 * `libfocal_adblock.so` for `arm64-v8a` (production devices) and `x86_64` (Android Emulator).
 *
 * Prerequisites on build host:
 *   rustup target add aarch64-linux-android x86_64-linux-android
 *   cargo install cargo-ndk
 *
 * Cross-compile into `src/main/jniLibs/<abi>/libfocal_adblock.so`:
 *   ./gradlew :core:adblock-rust:cargoNdkBuild
 *   # or directly:
 *   cargo ndk -t arm64-v8a -t x86_64 -o src/main/jniLibs build --manifest-path rust/Cargo.toml --release
 *
 * Host Rust unit tests (17 tests covering network rules, cosmetic CSS, URL cleaner & FFI unwind guard):
 *   cargo test --manifest-path core/adblock-rust/rust/Cargo.toml
 */

android {
    namespace = "app.focal.adblock"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val requiredNativeAbis = listOf("arm64-v8a", "x86_64")
val jniLibsDirFile: File = layout.projectDirectory.dir("src/main/jniLibs").asFile

fun findHostExecutable(binaryName: String): File? {
    val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    val candidateNames = if (isWindows) {
        listOf("$binaryName.exe", "$binaryName.cmd", binaryName)
    } else {
        listOf(binaryName)
    }
    val pathDirs = System.getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)
    val cargoHomeBin = File(
        System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.cargo",
        "bin",
    )
    return (pathDirs + cargoHomeBin).firstNotNullOfOrNull { dir ->
        candidateNames
            .map { name -> File(dir, name) }
            .firstOrNull { it.isFile && it.canExecute() }
    }
}

val cargoExecutable: File? = findHostExecutable("cargo")
val cargoNdkExecutable: File? = findHostExecutable("cargo-ndk")
val isCargoNdkAvailable: Boolean = cargoExecutable != null && cargoNdkExecutable != null

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Cross-compiles rust/focal_adblock into src/main/jniLibs/{arm64-v8a,x86_64}/libfocal_adblock.so via cargo-ndk."
    workingDir = layout.projectDirectory.dir("rust").asFile

    inputs.dir(layout.projectDirectory.dir("rust/src"))
    inputs.file(layout.projectDirectory.file("rust/Cargo.toml"))
    inputs.files(
        layout.projectDirectory.file("rust/Cargo.lock"),
        layout.projectDirectory.file("rust/.cargo/config.toml"),
    )
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))

    val cargoCmd = cargoExecutable?.absolutePath ?: "cargo"
    cargoNdkExecutable?.parentFile?.absolutePath?.let { cargoBinDir ->
        val existingPath = System.getenv("PATH").orEmpty()
        environment(
            "PATH",
            if (existingPath.isEmpty()) cargoBinDir else "$cargoBinDir${File.pathSeparator}$existingPath",
        )
    }

    commandLine(
        cargoCmd,
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        jniLibsDirFile.absolutePath,
        "build",
        "--release",
    )
}

val verifyReleaseNativeLibs = tasks.register("verifyReleaseNativeLibs") {
    group = "verification"
    description = "Fails Release builds loudly if libfocal_adblock.so is missing for any required Android ABI."
    if (isCargoNdkAvailable) {
        dependsOn(cargoNdkBuild)
    } else {
        mustRunAfter(cargoNdkBuild)
    }

    val expectedSoByAbi = requiredNativeAbis.associateWith { abi ->
        File(jniLibsDirFile, "$abi/libfocal_adblock.so")
    }
    val jniLibsPathDisplay = jniLibsDirFile.absolutePath

    doLast {
        val missingAbis = expectedSoByAbi
            .filterValues { soFile -> !soFile.isFile || soFile.length() == 0L }
            .keys
        if (missingAbis.isNotEmpty()) {
            throw GradleException(
                "Release build aborted: missing native Rust adblock library libfocal_adblock.so " +
                    "for ABI(s) [${missingAbis.joinToString(", ")}] in $jniLibsPathDisplay. " +
                    "Install cargo-ndk (`rustup target add aarch64-linux-android x86_64-linux-android && cargo install cargo-ndk`) " +
                    "or pre-stage 16 KB page-aligned libfocal_adblock.so artifacts before assembling Release.",
            )
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        if (isCargoNdkAvailable) {
            dependsOn(cargoNdkBuild)
        }
        if (name.contains("Release", ignoreCase = true)) {
            dependsOn(verifyReleaseNativeLibs)
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
