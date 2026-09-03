plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.DSH_Mobile"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.DSH_Mobile"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2-tables"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // 输出文件固定命名为 DSH-Mobile.apk（与使用者习惯一致，避免新旧包混淆）
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "DSH-Mobile.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material) // XML 主题 Theme.Material3.* 的来源
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.ext.tables)
    implementation(libs.datastore.preferences)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// ---------- 构建成功后自动把 APK 覆盖到桌面（DSH-Mobile.apk） ----------
// 目标目录解析优先级：
//   1) gradle 属性 -PapkDropDir=… 或环境变量 DSH_APK_DROP_DIR（显式指定）
//   2) 注册表中 Windows 桌面的真实位置（桌面可被重定向到其他盘，如 E:\Desktop）
//   3) 兜底 %USERPROFILE%\Desktop
val apkDropDir: File = run {
    val explicit = providers.gradleProperty("apkDropDir").orNull
        ?: providers.environmentVariable("DSH_APK_DROP_DIR").orNull
    explicit?.let { return@run File(it) }
    val home = System.getProperty("user.home") ?: "."
    val regDesktop = runCatching {
        val proc = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
            "/v", "Desktop",
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.readBytes().decodeToString()
        proc.waitFor()
        Regex("REG_(?:EXPAND_)?SZ\\s+(.+?)[\\r\\n]").find(text)
            ?.groupValues?.get(1)?.trim()
            ?.replace("%USERPROFILE%", home, ignoreCase = true)
    }.getOrNull()
    regDesktop?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
        ?: File(home, "Desktop")
}

// doLast 只在 assemble 成功时执行：构建失败绝不会用坏产物覆盖桌面的旧包。
// 用 matching+configureEach 惰性匹配：assemble<Variant> 任务由 AGP 在 afterEvaluate
// 才创建，脚本求值期 tasks.named 会直接报 not found。
listOf("debug" to "Debug", "release" to "Release").forEach { (variant, capitalized) ->
    tasks.matching { it.name == "assemble$capitalized" }.configureEach {
        doLast {
            val apk = layout.buildDirectory.file("outputs/apk/$variant/DSH-Mobile.apk").get().asFile
            if (!apk.isFile) {
                logger.lifecycle("未找到 $variant APK（${apk.absolutePath}），跳过桌面覆盖")
                return@doLast
            }
            val target = File(apkDropDir, "DSH-Mobile.apk")
            apk.copyTo(target, overwrite = true)
            logger.lifecycle("DSH-Mobile.apk 已覆盖到桌面：${target.absolutePath}")
        }
    }
}
