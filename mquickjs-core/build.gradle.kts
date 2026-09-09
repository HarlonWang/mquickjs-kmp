import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.binaryCompatibilityValidator)
}

// native/UPSTREAM 是上游 commit 的唯一真值，编译期注入到 BuildInfo，运行时可查引擎来源
val upstreamCommit = providers
    .fileContents(rootProject.layout.projectDirectory.file("native/UPSTREAM"))
    .asText
    .map { text ->
        text.lineSequence()
            .firstOrNull { it.startsWith("commit=") }
            ?.removePrefix("commit=")
            ?.trim()
            ?: error("native/UPSTREAM has no commit= line")
    }

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outDir = layout.buildDirectory.dir("generated/buildinfo/commonMain/kotlin")
    val sdkVersion = property("VERSION_NAME") as String
    inputs.property("sdkVersion", sdkVersion)
    inputs.property("upstreamCommit", upstreamCommit)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("wang/harlon/mquickjs/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package wang.harlon.mquickjs
            |
            |internal object BuildInfo {
            |    const val SDK_VERSION: String = "$sdkVersion"
            |    const val UPSTREAM_COMMIT: String = "${upstreamCommit.get()}"
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    android {
        namespace = "wang.harlon.mquickjs"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
