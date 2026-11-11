import com.android.build.api.variant.FilterConfiguration
import io.gitlab.arturbosch.detekt.Detekt
import java.util.Properties
import org.apache.commons.io.output.ByteArrayOutputStream

plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.room)
  alias(libs.plugins.detekt)
  alias(libs.plugins.about.libraries)
  alias(libs.plugins.kotlinx.serialization)
}

android {
  namespace = "live.sarok.chadmpv"
  compileSdk = 36

  sourceSets.getByName("main").jniLibs.srcDirs("src/main/jniLibs")

  defaultConfig {
    applicationId = "app.chadmpv"
    minSdk = 21
    targetSdk = 36
    versionCode = 12
    versionName = "0.1.6"

    vectorDrawables {
      useSupportLibrary = true
    }

    buildConfigField("String", "GIT_SHA", "\"${getCommitSha()}\"")
    buildConfigField("int", "GIT_COUNT", getCommitCount())
  }
  splits {
    abi {
      isEnable = true
      reset()
      include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
      isUniversalApk = true
    }
  }

  buildTypes {
    named("release") {
      isMinifyEnabled = true
      isShrinkResources = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
    create("preview") {
      initWith(getByName("release"))

      signingConfig = signingConfigs["debug"]
      applicationIdSuffix = ".preview"
      versionNameSuffix = "-${getCommitCount()}"
    }
    named("debug") {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-${getCommitCount()}"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
  buildFeatures {
    compose = true
    viewBinding = true
    buildConfig = true
  }
  composeCompiler {
    includeSourceInformation = true
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
  val abiCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
  )
  androidComponents {
    onVariants { variant ->
      variant.outputs.forEach { output ->
        val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
        output.versionCode.set((output.versionCode.orNull ?: 0) * 10 + (abiCodes[abi] ?: 0))
      }
    }
  }
  androidResources {
    generateLocaleConfig = true
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xwhen-guards", "-Xcontext-parameters")
  }
}

room {
  schemaDirectory("$projectDir/schemas")
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3.android)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  implementation(libs.bundles.compose.navigation3)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.compose.constraintlayout)
  implementation(libs.androidx.material3.icons.extended)
  implementation(libs.androidx.compose.animation.graphics)
  implementation(libs.material)
  implementation(libs.androidx.preferences.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.mediasession)
  implementation(libs.saveable)

  implementation(libs.mpv.lib)

  implementation(platform(libs.koin.bom))
  implementation(libs.bundles.koin)

  implementation(libs.seeker)
  implementation(libs.compose.prefs)
  implementation(libs.bundles.about.libs)
  implementation(libs.simple.icons)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.detekt.gradle.plugin)
  detektPlugins(libs.detekt.rules.compose)
  detektPlugins(libs.detekt.formatter)

  implementation(libs.kotlinx.immutable.collections)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.truetype.parser)
  implementation(libs.fsaf)
}

detekt {
  parallel = true
  allRules = false
  buildUponDefaultConfig = true
  config.setFrom("$rootDir/config/detekt/detekt.yml")
}

tasks.withType<Detekt>().configureEach {
  setSource(files(project.projectDir))
  exclude("**/build/**")
  reports {
    html.required.set(true)
    md.required.set(true)
  }
}

fun getCommitCount(): String = runCommand("git rev-list --count HEAD")
fun getCommitSha(): String = runCommand("git rev-parse --short HEAD")
fun runCommand(command: String): String {
  val stdOut = ByteArrayOutputStream()
  exec {
    commandLine = command.split(' ')
    standardOutput = stdOut
  }
  return String(stdOut.toByteArray()).trim()
}

// --- Rust native scanner (rust/libchadscan) ---------------------------------

data class RustScannerTarget(val triple: String, val abi: String, val clang: String)

val rustScannerTargets = listOf(
  RustScannerTarget("armv7-linux-androideabi", "armeabi-v7a", "armv7a-linux-androideabi28-clang"),
  RustScannerTarget("aarch64-linux-android", "arm64-v8a", "aarch64-linux-android28-clang"),
)

val rustScannerDir = rootProject.layout.projectDirectory.dir("rust")
val rustScannerJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val rustScannerLocalProperties = rootProject.file("local.properties")

fun resolveAndroidNdkDir(): File? {
  val fromEnv = System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }
    ?: System.getenv("ANDROID_NDK_ROOT")?.takeIf { it.isNotBlank() }
  if (fromEnv != null) {
    val dir = File(fromEnv)
    if (dir.isDirectory) return dir
  }

  if (rustScannerLocalProperties.isFile) {
    val properties = Properties()
    rustScannerLocalProperties.inputStream().use { properties.load(it) }
    val sdkDir = properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
    if (sdkDir != null) {
      val ndkRoot = File(sdkDir, "ndk")
      val newest = ndkRoot.listFiles { file -> file.isDirectory }?.maxByOrNull { it.name }
      if (newest != null) return newest
    }
  }

  return File("/home/sarok/Android/Sdk/ndk/28.2.13676358").takeIf { it.isDirectory }
}

fun findCargoExecutable(): String? {
  val candidate = System.getenv("CARGO")?.takeIf { it.isNotBlank() } ?: "cargo"
  return try {
    val process = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
    process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() == 0) candidate else null
  } catch (_: Exception) {
    null
  }
}

val buildRustScanner = tasks.register("buildRustScanner") {
  group = "build"
  description = "Cross-compiles the Rust chadscan JNI library for the supported Android ABIs."

  inputs.dir(rustScannerDir.dir("src"))
  inputs.file(rustScannerDir.file("Cargo.toml"))
  inputs.file(rustScannerDir.file("Cargo.lock"))
  inputs.file(rustScannerDir.file(".cargo/config.toml"))
  rustScannerTargets.forEach { target ->
    outputs.file(rustScannerJniLibsDir.file("${target.abi}/libchadscan.so"))
  }

  doLast {
    val cargo = findCargoExecutable()
    if (cargo == null) {
      logger.lifecycle("cargo not found; skipping Rust scanner build and using prebuilt jniLibs.")
      return@doLast
    }

    val ndkDir = resolveAndroidNdkDir()
    val ndkBinDir = ndkDir?.let { File(it, "toolchains/llvm/prebuilt/linux-x86_64/bin") }
    if (ndkDir == null) {
      logger.warn("Android NDK not found; cargo will use the linker from rust/.cargo/config.toml.")
    }

    rustScannerTargets.forEach { target ->
      val builder = ProcessBuilder(cargo, "build", "--release", "--target", target.triple)
        .directory(rustScannerDir.asFile)
        .redirectErrorStream(true)

      val linker = ndkBinDir?.let { File(it, target.clang) }
      if (linker != null && linker.isFile) {
        val envKey = "CARGO_TARGET_${target.triple.uppercase().replace('-', '_')}_LINKER"
        builder.environment()[envKey] = linker.absolutePath
      } else if (ndkDir != null) {
        logger.warn("NDK linker ${target.clang} not found; relying on rust/.cargo/config.toml.")
      }

      logger.lifecycle("Building Rust scanner for ${target.triple} (${target.abi})")
      val process = builder.start()
      val output = process.inputStream.bufferedReader().use { it.readText() }
      val exitCode = process.waitFor()
      if (exitCode != 0) {
        throw GradleException("cargo build failed for ${target.triple} (exit $exitCode):\n$output")
      }

      val builtSo = File(rustScannerDir.asFile, "target/${target.triple}/release/libchadscan.so")
      if (!builtSo.isFile) {
        throw GradleException("cargo reported success but ${builtSo.path} is missing")
      }

      val destination = rustScannerJniLibsDir.file("${target.abi}/libchadscan.so").asFile
      destination.parentFile.mkdirs()
      builtSo.copyTo(destination, overwrite = true)
      logger.lifecycle("Staged ${destination.path}")
    }
  }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
  .configureEach { dependsOn(buildRustScanner) }

tasks.named("preBuild") { dependsOn(buildRustScanner) }

aboutLibraries {
  excludeFields = arrayOf("generated")
}
