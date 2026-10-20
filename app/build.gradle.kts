plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.flexiatom.floatingassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flexiatom.floatingassistant"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.5.0"
        // 端侧 MT 原生库仅 arm64（STQ NEON dotprod 特化，验证设备 abi=arm64-v8a）。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.core)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.runtime)
    implementation(libs.onnxruntime.android)
    // TTS（tts-engine-replacement 读法①）：sherpa-onnx 官方 AAR 的**派生物**——只留 arm64-v8a，
    // 并删掉它自带的 libonnxruntime.so（1.28.2）。不删就有两份同名 lib 撞进同一个 APK；而 OCR 那条
    // 已定档的路用 Maven 1.29.0 的 Java 绑定，赢的必须是 1.29.0 —— sherpa-jni 跑在比构建时**更新**
    // 的 ORT 上是安全方向，反过来（1.29 的 4j JNI 接 1.28 原生）才是会炸的那一侧。来源/哈希/改法见
    // bench/models/MANIFEST.md 的 TTS 段；原始 AAR 在 ~/Downloads（不进 git、也不在 /tmp）。
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}

// §3.1b：构建时校验端侧 MT 原生库（jniLibs 里的 .so 由 native/llama-stq/build-android.sh 重建）。
// 仅当 ANDROID_NDK 可用且 .so 已放入时才校验——过期/错 .so 直接 fail 构建；
// .so 缺席则跳过（纯 Java 开发仍可 assembleDebug，MT 运行时降级 PassthroughMtEngine）。
val ndkDir = providers.environmentVariable("ANDROID_NDK").orNull
val jniDir = file("src/main/jniLibs/arm64-v8a")
val stqScript = rootProject.file("native/llama-stq/verify-stq.sh")

val verifyStqSo = tasks.register("verifyStqSo") {
    group = "verification"
    description = "校验 arm64 .so 携带 STQ NEON 核与 JNI 导出"
    doLast {
        val cpu = File(jniDir, "libggml-cpu.so")
        val mtjni = File(jniDir, "libmtjni.so")
        if (ndkDir.isNullOrBlank()) {
            logger.lifecycle("verifyStqSo: ANDROID_NDK 未设，跳过 STQ .so 校验")
            return@doLast
        }
        if (!mtjni.isFile() || !cpu.isFile()) {
            logger.warn("verifyStqSo: jniLibs 缺 .so（mtjni=${mtjni.isFile()} cpu=${cpu.isFile()}）；跳过。跑 build-android.sh 后拷入以启用 MT。")
            return@doLast
        }
        listOf<Pair<String, java.io.File>>(
            "so" to cpu,     // libggml-cpu.so：aarch64 + NEON ggml_vec_dot_stq1_0_q8_K
            "jni" to mtjni,  // libmtjni.so：aarch64 + native* 导出 + NEEDED libllama
        ).forEach { (mode, target) ->
            val pb = ProcessBuilder("bash", stqScript.absolutePath, mode, target.absolutePath)
            pb.environment()["ANDROID_NDK"] = ndkDir
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            print(output)
            if (rc != 0) {
                throw GradleException("verifyStqSo FAIL: $mode ${target.name} rc=$rc（过期/错 .so，重跑 build-android.sh）")
            }
        }
        logger.lifecycle("verifyStqSo: PASS (STQ NEON 核 + JNI 导出已校验)")
    }
}

tasks.named("preBuild") {
    dependsOn(verifyStqSo)
}
