import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("jacoco")
}

fun gitLine(root: File, vararg cmd: String): String {
    return try {
        val p = ProcessBuilder(*cmd)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().use { it.readText().trim() }
        p.waitFor(8, TimeUnit.SECONDS)
        text
    } catch (_: Exception) {
        ""
    }
}

fun gitCommitCount(root: File): Int =
    gitLine(root, "git", "rev-list", "--count", "HEAD").toIntOrNull() ?: 0

/**
 * 优先顺序（与打完的 APK 完全一致，中继 /version 才能对上）：
 * 1) Gradle -PotaVersionCode=（publish_ota.sh 每次递增传入）
 * 2) 仓库根 [.ota_build_counter]（脚本写入，普通人不用管）
 * 3) 无则 10000+Git 提交数（只 commit 不跑脚本时仍会变）
 */
fun otaVersionCode(project: org.gradle.api.Project): Int {
    project.rootProject.findProperty("otaVersionCode")?.toString()?.toIntOrNull()
        ?.let { return it.coerceIn(1, Int.MAX_VALUE) }
    val f = project.rootProject.file(".ota_build_counter")
    if (f.exists()) {
        val n = f.readText().trim().toIntOrNull()
        if (n != null && n > 0) return n.coerceIn(1, Int.MAX_VALUE)
    }
    return (10_000 + gitCommitCount(project.rootProject.projectDir)).coerceIn(1, Int.MAX_VALUE)
}

fun otaVersionName(project: org.gradle.api.Project): String {
    project.rootProject.findProperty("otaVersionName")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    val vc = otaVersionCode(project)
    val git = gitLine(project.rootProject.projectDir, "git", "describe", "--tags", "--always", "--dirty").ifEmpty { "dev" }
    return "$git-b$vc"
}

/**
 * OTA 更新说明：优先 -PotaUpdateMessage=，其次仓库根 ota_update_message.txt，否则最近一条 git commit。
 */
fun otaUpdateMessage(project: org.gradle.api.Project): String {
    project.rootProject.findProperty("otaUpdateMessage")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    val f = project.rootProject.file("ota_update_message.txt")
    if (f.exists()) {
        val t = f.readText(StandardCharsets.UTF_8).trim()
        if (t.isNotEmpty()) return t
    }
    return gitLine(project.rootProject.projectDir, "git", "log", "-1", "--pretty=%B").ifEmpty { "本地构建" }
}

fun jsonEscapeForField(s: String): String =
    buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

val otaVc = otaVersionCode(project)
val otaVn = otaVersionName(project)

android {
    namespace = "com.cdp.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cdp.remote.dev"
        minSdk = 26
        targetSdk = 34
        versionCode = otaVc
        versionName = otaVn

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "CdpRemote-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat-resources:1.6.1")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore:1.0.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

tasks.register("writeOtaPublishMeta") {
    group = "ota"
    description = "写入 build/ota/version.json（与本次打包的 APK versionCode 一致）"
    doLast {
        val root = rootProject.projectDir
        val dir = File(root, "build/ota")
        dir.mkdirs()
        val vc = otaVersionCode(project)
        val vn = otaVersionName(project)
        val msg = otaUpdateMessage(project)
        val body =
            "{\"versionCode\":$vc,\"versionName\":${jsonEscapeForField(vn)},\"updateMessage\":${jsonEscapeForField(msg)}}"
        File(dir, "version.json").writeText(body, StandardCharsets.UTF_8)
    }
}

afterEvaluate {
    listOf("assembleDebug", "assembleRelease").forEach { taskName ->
        tasks.named(taskName).configure {
            finalizedBy(tasks.named("writeOtaPublishMeta"))
        }
    }
}
