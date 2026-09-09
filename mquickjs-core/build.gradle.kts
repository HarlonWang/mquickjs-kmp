import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.file.FileSystemOperations
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.FileOutputStream
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.binaryCompatibilityValidator)
}

val nativeDir: Directory = rootProject.layout.projectDirectory.dir("native")
val nativeBuildDir: Provider<Directory> = layout.buildDirectory.dir("native")

// native/UPSTREAM 是上游 commit 的唯一真值，编译期注入到 BuildInfo，运行时可查引擎来源
val upstreamCommitFromPin = providers
    .fileContents(nativeDir.file("UPSTREAM"))
    .asText
    .map { text ->
        text.lineSequence()
            .firstOrNull { it.startsWith("commit=") }
            ?.removePrefix("commit=")
            ?.trim()
            ?: error("native/UPSTREAM has no commit= line")
    }

abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val sdkVersion: Property<String>

    @get:Input
    abstract val upstreamCommit: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDir.get().file("wang/harlon/mquickjs/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package wang.harlon.mquickjs
            |
            |internal object BuildInfo {
            |    const val SDK_VERSION: String = "${sdkVersion.get()}"
            |    const val UPSTREAM_COMMIT: String = "${upstreamCommit.get()}"
            |}
            |""".trimMargin()
        )
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfo>("generateBuildInfo") {
    sdkVersion.set(providers.gradleProperty("VERSION_NAME"))
    upstreamCommit.set(upstreamCommitFromPin)
    outputDir.set(layout.buildDirectory.dir("generated/buildinfo/commonMain/kotlin"))
}

// ---- native build: host tool -> generated stdlib headers -> CMake per target (docs/native-build.md)

abstract class BuildHostTool @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:InputDirectory
    abstract val includeDir: DirectoryProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun build() {
        val out = output.get().asFile
        out.parentFile.mkdirs()
        execOps.exec {
            commandLine(
                "cc", "-O2", "-D_GNU_SOURCE", "-I", includeDir.get().asFile.absolutePath,
                "-o", out.absolutePath, *sources.files.map { it.absolutePath }.toTypedArray(),
            )
        }
    }
}

abstract class GenerateStdlib @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputFile
    abstract val tool: RegularFileProperty

    @get:Input
    abstract val wordSize: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val flags = if (wordSize.get() == 32) listOf("-m32") else emptyList()
        run(dir.resolve("kmp_stdlib.h"), flags)
        run(dir.resolve("mquickjs_atom.h"), listOf("-a") + flags)
    }

    private fun run(target: java.io.File, args: List<String>) {
        FileOutputStream(target).use { stream ->
            execOps.exec {
                commandLine(listOf(tool.get().asFile.absolutePath) + args)
                standardOutput = stream
            }
        }
    }
}

abstract class CMakeBuild @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:InputDirectory
    abstract val generatedDir: DirectoryProperty

    @get:Input
    abstract val cmakeArgs: ListProperty<String>

    @get:Internal
    abstract val cmakeBuildDir: DirectoryProperty

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val libDir: DirectoryProperty

    @TaskAction
    fun build() {
        val buildDir = cmakeBuildDir.get().asFile
        val lib = libDir.get().asFile
        buildDir.mkdirs()
        lib.mkdirs()
        execOps.exec {
            commandLine(
                listOf(
                    "cmake", "-S", sourceDir.get().asFile.absolutePath, "-B", buildDir.absolutePath,
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DMQJS_GEN_DIR=" + generatedDir.get().asFile.absolutePath,
                    "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=" + lib.absolutePath,
                    "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=" + lib.absolutePath,
                ) + cmakeArgs.get(),
            )
        }
        execOps.exec {
            commandLine("cmake", "--build", buildDir.absolutePath, "--config", "Release", "--parallel")
        }
    }
}

abstract class CollectJniLibs @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    /** Each entry is `<...>/android/<abi>/lib`; the ABI is read back from the path. */
    @get:InputFiles
    abstract val abiLibDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() {
        val out = outputDir.get().asFile
        fs.delete { delete(out) }
        abiLibDirs.files.forEach { dir ->
            fs.copy {
                from(dir) { include("*.so") }
                into(out.resolve(dir.parentFile.name))
            }
        }
    }
}

val nativeSources = fileTree(nativeDir) {
    include("CMakeLists.txt", "mquickjs/**", "shim/**", "stdlib/**", "jni/**", "patches/**")
}

val buildHostTool = tasks.register<BuildHostTool>("buildHostTool") {
    sources.from(nativeDir.file("stdlib/kmp_stdlib.c"), nativeDir.file("mquickjs/mquickjs_build.c"))
    includeDir.set(nativeDir.dir("mquickjs"))
    output.set(nativeBuildDir.map { it.file("host/kmp_stdlib") })
}

val generateStdlib64 = tasks.register<GenerateStdlib>("generateStdlib64") {
    tool.set(buildHostTool.flatMap { it.output })
    wordSize.set(64)
    outputDir.set(nativeBuildDir.map { it.dir("gen64") })
}

val generateStdlib32 = tasks.register<GenerateStdlib>("generateStdlib32") {
    tool.set(buildHostTool.flatMap { it.output })
    wordSize.set(32)
    outputDir.set(nativeBuildDir.map { it.dir("gen32") })
}

val androidSdkDir: Provider<String> = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { Properties().apply { load(it.reader()) }.getProperty("sdk.dir") }
    .orElse(providers.environmentVariable("ANDROID_HOME"))
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))

val androidAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val androidNdkVersion = libs.versions.android.ndk.get()
val androidMinSdk = libs.versions.android.minSdk.get()

val androidNativeTasks = androidAbis.map { abi ->
    val generated = if (abi == "armeabi-v7a") generateStdlib32 else generateStdlib64
    // 拷成局部变量：lambda 里直接引用脚本级 val 会捕获整个脚本对象，configuration cache 无法序列化
    val ndkVersion = androidNdkVersion
    val minSdk = androidMinSdk
    tasks.register<CMakeBuild>("buildNativeAndroid" + abi.replace("-", "_").replaceFirstChar { it.uppercase() }) {
        sources.from(nativeSources)
        sourceDir.set(nativeDir)
        generatedDir.set(generated.flatMap { it.outputDir })
        cmakeBuildDir.set(nativeBuildDir.map { it.dir("android/$abi/cmake") })
        libDir.set(nativeBuildDir.map { it.dir("android/$abi/lib") })
        cmakeArgs.set(
            androidSdkDir.map { sdk ->
                listOf(
                    "-DCMAKE_TOOLCHAIN_FILE=$sdk/ndk/$ndkVersion/build/cmake/android.toolchain.cmake",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-$minSdk",
                    "-DANDROID_STL=none",
                )
            },
        )
    }
}

val collectJniLibs = tasks.register<CollectJniLibs>("collectJniLibs") {
    abiLibDirs.from(androidNativeTasks.map { task -> task.flatMap { it.libDir } })
    outputDir.set(nativeBuildDir.map { it.dir("jniLibs") })
}

data class AppleTarget(val sdk: String, val systemName: String, val arch: String, val deploymentTarget: String)

val appleTargets = mapOf(
    "iosArm64" to AppleTarget("iphoneos", "iOS", "arm64", "12.0"),
    "iosSimulatorArm64" to AppleTarget("iphonesimulator", "iOS", "arm64", "12.0"),
    "macosArm64" to AppleTarget("macosx", "Darwin", "arm64", "11.0"),
)

val appleNativeTasks = appleTargets.mapValues { (name, target) ->
    tasks.register<CMakeBuild>("buildNative" + name.replaceFirstChar { it.uppercase() }) {
        sources.from(nativeSources)
        sourceDir.set(nativeDir)
        generatedDir.set(generateStdlib64.flatMap { it.outputDir })
        cmakeBuildDir.set(nativeBuildDir.map { it.dir("apple/$name/cmake") })
        libDir.set(nativeBuildDir.map { it.dir("apple/$name/lib") })
        cmakeArgs.set(
            listOf(
                "-DCMAKE_SYSTEM_NAME=${target.systemName}",
                "-DCMAKE_OSX_SYSROOT=${target.sdk}",
                "-DCMAKE_OSX_ARCHITECTURES=${target.arch}",
                "-DCMAKE_OSX_DEPLOYMENT_TARGET=${target.deploymentTarget}",
                "-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY",
            ),
        )
    }
}

kotlin {
    android {
        namespace = "wang.harlon.mquickjs"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // JNI 只能在设备上加载，commonTest 全部走 device test，不建 host test
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        optimization {
            consumerKeepRules.file(nativeDir.file("consumer-rules.pro"))
        }

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        val nativeTask = appleNativeTasks.getValue(name)
        compilations.getByName("main").cinterops.create("mquickjs") {
            definitionFile.set(nativeDir.file("mquickjs_kmp.def"))
            includeDirs(nativeDir.dir("shim"))
            extraOpts("-libraryPath", nativeTask.flatMap { it.libDir }.get().asFile.absolutePath)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.runner)
        }
    }
}

tasks.matching { it.name.startsWith("cinteropMquickjs") }.configureEach {
    val targetName = name.removePrefix("cinteropMquickjs").replaceFirstChar { it.lowercase() }
    appleNativeTasks[targetName]?.let { dependsOn(it) }
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension>("androidComponents") {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(collectJniLibs) { it.outputDir }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(artifactId = "mquickjs-core")

    pom {
        name.set("mquickjs-core")
        description.set("Kotlin Multiplatform bindings for MicroQuickJS, the embedded-systems JavaScript engine by Fabrice Bellard.")
        url.set("https://github.com/HarlonWang/mquickjs-kmp")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("HarlonWang")
                name.set("HarlanWang")
                url.set("https://github.com/HarlonWang")
            }
        }
        scm {
            url.set("https://github.com/HarlonWang/mquickjs-kmp")
            connection.set("scm:git:git://github.com/HarlonWang/mquickjs-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/HarlonWang/mquickjs-kmp.git")
        }
    }
}

@OptIn(kotlinx.validation.ExperimentalBCVApi::class)
apiValidation {
    klib {
        enabled = true
    }
}
