import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.binaryCompatibilityValidator)
}

kotlin {
    android {
        namespace = "wang.harlon.mquickjs.serialization"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}

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
        commonMain.dependencies {
            api(project(":library"))
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Android host test 走 library 编出来的宿主 JNI 库（library/build.gradle.kts 的 hostJniElements）
val hostJni by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named("mquickjs-host-jni"))
}

dependencies {
    hostJni(project(":library"))
}

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    inputs.files(hostJni)
    val libDir = hostJni.elements.map { it.single().asFile.absolutePath }
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Djava.library.path=${libDir.get()}") })
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(artifactId = "mquickjs-kmp-serialization")

    pom {
        name.set("mquickjs-kmp-serialization")
        description.set("kotlinx.serialization bridge for mquickjs-kmp: typed values across the JavaScript boundary.")
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
