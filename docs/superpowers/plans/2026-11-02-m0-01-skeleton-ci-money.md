# M0 Part 1: Skeleton, CI and Money Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-02-m0-00-index.md` first: its locked decisions and type inventory apply here.

**Goal:** A buildable Android project with `fake`/`real` flavors and a Hilt app shell, CI on every push, a tag-triggered signed release, the `money` package, and the CI check that keeps domain packages free of Android imports and floating point.

**Architecture:** One Gradle module `:app` under `android/`. Domain code lives in Android-free packages in `src/main`, tested as plain JVM unit tests with JUnit 5. A JVM test (`DomainPurityTest`) scans the source tree, so CI enforces the purity rules without extra scripts.

**Tech Stack:** Gradle 8.14.3 (Kotlin DSL, version catalog), AGP 8.13.0, Kotlin 2.2.20, KSP, Hilt 2.57.1, Jetpack Compose (BOM 2025.09.00), JUnit 5.13.4, Kotest 5.9.1, GitHub Actions.

## Global Constraints

- applicationId `io.github.codex07.iou`; display name "IOU"; the `fake` flavor adds the suffix `.fake` so it installs side by side (spec §2.4).
- minSdk **30**, compileSdk and targetSdk 36 (spec §2.3).
- Flavors `fake` and `real` in one dimension (spec §2.4).
- `Double`/`Float` never appear in `money`, `ledger`, `api`, `data`, `io` sources (spec §3.1, §11).
- `money`, `ledger`, `api`, `fake` contain no Android imports (spec §2.1).
- Money is `Long` minor units + ISO 4217 code; minor-unit digits from `java.util.Currency.defaultFractionDigits` (spec §3.1).
- Every stored amount lies between 0 and `MAX_MINOR = 10^13` minor units; larger input → `AmountTooLarge`; `Long` arithmetic uses `Math.addExact`/`subtractExact`/`multiplyExact`; `BigDecimal` → `Long` uses `longValueExact()` (spec §3.1).
- CI on push: lint, JVM tests, the `Double`/`Float` check (spec §2.4). Release: a `v*` tag builds `realRelease`, signs it with the keystore from repository secrets, attaches the APK to a GitHub Release (spec §2.4).
- No attribution lines in commits. Commit after every task.

## File map

| File | Responsibility |
|---|---|
| `android/settings.gradle.kts` | Repositories, `:app` include |
| `android/build.gradle.kts` | Plugin declarations (`apply false`) |
| `android/gradle.properties` | Gradle/AndroidX flags |
| `android/gradle/libs.versions.toml` | Every dependency and plugin version |
| `android/gradle/wrapper/*`, `android/gradlew`, `android/gradlew.bat` | Gradle wrapper |
| `android/gradle/gradle-daemon-jvm.properties` | Runs Gradle on Java 21 everywhere (CLI, Android Studio sync, CI) |
| `android/.editorconfig` | Shared code style |
| `android/app/build.gradle.kts` | Android config, flavors, signing, dependencies, JUnit 5 |
| `android/app/src/main/AndroidManifest.xml` | Application and launcher activity |
| `android/app/src/main/kotlin/io/github/codex07/iou/IouApplication.kt` | `@HiltAndroidApp` application |
| `android/app/src/main/kotlin/io/github/codex07/iou/MainActivity.kt` | Placeholder Compose activity (the friend replaces its content) |
| `android/app/src/main/kotlin/io/github/codex07/iou/money/CurrencyCode.kt` | Validated ISO 4217 code + minor-unit digits |
| `android/app/src/main/kotlin/io/github/codex07/iou/money/Money.kt` | `Money`, `MAX_MINOR`, exact arithmetic, plain formatting |
| `android/app/src/main/kotlin/io/github/codex07/iou/money/MoneyParser.kt` | Text → `Money` via `BigDecimal` |
| `android/app/src/test/kotlin/io/github/codex07/iou/money/*Test.kt` | Money tests |
| `android/app/src/test/kotlin/io/github/codex07/iou/DomainPurityTest.kt` | Android-import and floating-point scan |
| `.github/workflows/ci.yml` | Build, lint, tests on push and PR |
| `.github/workflows/release.yml` | Tag → signed `realRelease` APK → GitHub Release |

Commands run from `android/` in Git Bash (`./gradlew …`). In PowerShell use `.\gradlew.bat …` with the same arguments.

---

### Task 1: Toolchain, Gradle skeleton, flavors and Hilt app shell

**Files:**
- Create: `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradle/gradle-daemon-jvm.properties`
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/.editorconfig`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/kotlin/io/github/codex07/iou/IouApplication.kt`, `.../MainActivity.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: variants `fakeDebug`, `fakeRelease`, `realDebug`, `realRelease`; `BuildConfig.FLAVOR`; string resource `app_name`; JUnit 5 enabled for every unit-test task; the `libs` catalog aliases used by later parts (`libs.kotlinx.coroutines.core`, `libs.kotest.property`, `libs.turbine`, …).

- [ ] **Step 1 (owner, by hand): Install the toolchain**

Install Android Studio (stable) with the default components: it brings the Android SDK. Then, in Android Studio's SDK Manager, install **Android SDK Platform 36** and the latest **Build-Tools**, and create one emulator (any Pixel image with API 30 or newer).

Install a separate **JDK 21** (done: Microsoft OpenJDK 21). Don't use Studio's bundled JBR: it is Java 25, and Gradle 8.14.x can't run on Java 25 (that needs Gradle 9.1+, which comes with the AGP 9 upgrade chore). Set these user environment variables and open a new terminal:

```
JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot
ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
PATH += %JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools
```

Check:

```bash
java -version
adb version
```

Expected: a Java 21 version line, and an `Android Debug Bridge version …` line.

Recent Android Studio versions have no Gradle JDK setting for projects that declare daemon JVM criteria; Step 2 adds that file, so Studio's sync runs Gradle on Java 21 too. If sync reports that no matching JDK was found, add this line to the user-level `%USERPROFILE%\.gradle\gradle.properties` (never the repo):

```properties
org.gradle.java.installations.paths=C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot
```

- [ ] **Step 2: Get the Gradle wrapper files**

The wrapper is easiest to take from a throwaway project. In Android Studio: *New Project → Empty Activity*, save it **outside the repo** (e.g. `D:\Workspace\wrapper-tmp`). Copy exactly these into `android/`:

```bash
cp /d/Workspace/wrapper-tmp/gradlew /d/Workspace/wrapper-tmp/gradlew.bat /d/Workspace/IOU-App/android/
mkdir -p /d/Workspace/IOU-App/android/gradle/wrapper
cp /d/Workspace/wrapper-tmp/gradle/wrapper/gradle-wrapper.jar /d/Workspace/wrapper-tmp/gradle/wrapper/gradle-wrapper.properties /d/Workspace/IOU-App/android/gradle/wrapper/
```

Then replace `android/gradle/wrapper/gradle-wrapper.properties` with:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Create `android/gradle/gradle-daemon-jvm.properties` (Gradle's daemon JVM criteria). It makes the command line, Android Studio's sync and CI all run Gradle on Java 21. The app still compiles to Java 17 bytecode (`jvmTarget` in Step 4). There are no download URLs and no foojay plugin: Gradle uses an installed JDK 21 (`JAVA_HOME` locally, `setup-java` on CI):

```properties
toolchainVersion=21
```

Delete the throwaway project. Mark `gradlew` executable for Linux CI:

```bash
cd /d/Workspace/IOU-App && git add android/gradlew && git update-index --chmod=+x android/gradlew
```

- [ ] **Step 3: Write the Gradle settings, root build, properties and version catalog**

`android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "IOU"
include(":app")
```

`android/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

`android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`android/gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.13.0"
kotlin = "2.2.20"
ksp = "2.2.20-2.0.3"
hilt = "2.57.1"
androidxHilt = "1.2.0"
composeBom = "2025.09.00"
activityCompose = "1.10.1"
coreKtx = "1.16.0"
lifecycle = "2.9.2"
coroutines = "1.10.2"
junit = "5.13.4"
kotest = "5.9.1"
turbine = "1.2.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
androidx-hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "androidxHilt" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
kotest-assertions-core = { module = "io.kotest:kotest-assertions-core", version.ref = "kotest" }
kotest-property = { module = "io.kotest:kotest-property", version.ref = "kotest" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

`android/.editorconfig`:

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 4

[*.{yml,yaml,toml,json}]
indent_size = 2
```

- [ ] **Step 4: Write the app module build file**

`android/app/build.gradle.kts`. Signing values come from environment variables (CI) or an untracked `android/keystore.properties` (local); with neither, release builds are simply unsigned. `RELEASE_VERSION_NAME`/`RELEASE_VERSION_CODE` let the release workflow stamp the tag and a growing version code, so a newer release always installs over an older one.

```kotlin
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    System.getenv(env) ?: keystoreProperties.getProperty(property)

val releaseStoreFile = signingValue("storeFile", "RELEASE_STORE_FILE")

android {
    namespace = "io.github.codex07.iou"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.codex07.iou"
        minSdk = 30
        targetSdk = 36
        versionCode = System.getenv("RELEASE_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "0.0.1"
    }

    flavorDimensions += "backend"
    productFlavors {
        create("fake") {
            dimension = "backend"
            applicationIdSuffix = ".fake"
            versionNameSuffix = "-fake"
            resValue("string", "app_name", "IOU (fake)")
        }
        create("real") {
            dimension = "backend"
            resValue("string", "app_name", "IOU")
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
```

Minification stays off in M0: sideloaded APK size doesn't matter, and it avoids keep-rule work before serialization arrives in M1.

- [ ] **Step 5: Write the manifest and the app shell**

`android/app/src/main/AndroidManifest.xml`. Auto Backup stays off until M1 adds the backup rules (spec §9, §14 item 5); no `INTERNET` permission until the FX client exists.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".IouApplication"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:usesCleartextTraffic="false"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/kotlin/io/github/codex07/iou/IouApplication.kt`:

```kotlin
package io.github.codex07.iou

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class IouApplication : Application()
```

`android/app/src/main/kotlin/io/github/codex07/iou/MainActivity.kt` (a placeholder; the UI contributor owns what it shows):

```kotlin
package io.github.codex07.iou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
                        Text("IOU (${BuildConfig.FLAVOR}): UI goes here")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Build both flavors**

Open `android/` once in Android Studio so it writes `android/local.properties` (`sdk.dir=…`; ignored by git), or rely on `ANDROID_HOME`. Then:

```bash
./gradlew assembleFakeDebug assembleRealDebug
```

Expected: `BUILD SUCCESSFUL`, and two APKs: `app/build/outputs/apk/fake/debug/app-fake-debug.apk` and `app/build/outputs/apk/real/debug/app-real-debug.apk`.

The wrapper scripts and jar came from a newer Gradle's template. Regenerate them from the pinned version so they match, then rebuild once:

```bash
./gradlew wrapper --gradle-version 8.14.3
./gradlew assembleFakeDebug
```

Expected: `gradle-wrapper.properties` still points to `gradle-8.14.3-bin.zip`, and the build is still `BUILD SUCCESSFUL`.

If Gradle reports that one of the pinned versions can't be found, take the nearest existing patch release of the same minor version from Google Maven / Maven Central, put it in `libs.versions.toml`, and update locked decision 9 in the index in the same commit.

- [ ] **Step 7: Run it on the emulator**

Start the emulator, then:

```bash
./gradlew installFakeDebug installRealDebug
adb shell am start -n io.github.codex07.iou.fake/io.github.codex07.iou.MainActivity
```

Expected: the screen shows `IOU (fake): UI goes here`. The launcher shows two apps, "IOU (fake)" and "IOU".

- [ ] **Step 8: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/
git status --short
git commit -m "Add the Android project skeleton with fake and real flavors and a Hilt app shell"
```

Check `git status --short` before committing: `local.properties`, `.gradle/`, `build/` and `.idea/` must not be listed (they're ignored by `android/.gitignore`).

---

### Task 2: `CurrencyCode` and `Money`

**Files:**
- Create: `android/app/src/main/kotlin/io/github/codex07/iou/money/CurrencyCode.kt`
- Create: `android/app/src/main/kotlin/io/github/codex07/iou/money/Money.kt`
- Test: `android/app/src/test/kotlin/io/github/codex07/iou/money/CurrencyCodeTest.kt`
- Test: `android/app/src/test/kotlin/io/github/codex07/iou/money/MoneyTest.kt`

**Interfaces:**
- Consumes: the Task 1 build (JUnit 5, Kotest).
- Produces:
  - `@JvmInline value class CurrencyCode(val code: String)`: `val fractionDigits: Int`; `companion fun parse(text: String): CurrencyCode?`; `toString()` returns the code.
  - `const val MAX_MINOR: Long = 10_000_000_000_000L`
  - `data class Money(val amountMinor: Long, val currency: CurrencyCode)`: `operator fun plus(other: Money): Money`, `operator fun minus(other: Money): Money`, `operator fun unaryMinus(): Money`, `fun toPlainString(): String`, `toString()` = `"<plain> <code>"`, `companion fun zero(currency: CurrencyCode): Money`.

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/kotlin/io/github/codex07/iou/money/CurrencyCodeTest.kt`:

```kotlin
package io.github.codex07.iou.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CurrencyCodeTest {

    @Test
    fun `known codes expose their minor-unit digits`() {
        CurrencyCode("EUR").fractionDigits shouldBe 2
        CurrencyCode("JPY").fractionDigits shouldBe 0
        CurrencyCode("BHD").fractionDigits shouldBe 3
    }

    @Test
    fun `parse trims and uppercases`() {
        CurrencyCode.parse(" sek ") shouldBe CurrencyCode("SEK")
    }

    @Test
    fun `parse rejects unknown codes, malformed codes and codes without minor units`() {
        CurrencyCode.parse("QQQ") shouldBe null
        CurrencyCode.parse("XAU") shouldBe null
        CurrencyCode.parse("EURO") shouldBe null
        CurrencyCode.parse("") shouldBe null
    }

    @Test
    fun `the constructor rejects unsupported codes`() {
        shouldThrow<IllegalArgumentException> { CurrencyCode("QQQ") }
        shouldThrow<IllegalArgumentException> { CurrencyCode("eur") }
    }

    @Test
    fun `toString is the bare code`() {
        CurrencyCode("EUR").toString() shouldBe "EUR"
    }
}
```

`android/app/src/test/kotlin/io/github/codex07/iou/money/MoneyTest.kt`:

```kotlin
package io.github.codex07.iou.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MoneyTest {

    private val eur = CurrencyCode("EUR")
    private val sek = CurrencyCode("SEK")

    @Test
    fun `adds, subtracts and negates within one currency`() {
        Money(1050, eur) + Money(250, eur) shouldBe Money(1300, eur)
        Money(1050, eur) - Money(2000, eur) shouldBe Money(-950, eur)
        -Money(5, eur) shouldBe Money(-5, eur)
    }

    @Test
    fun `mixing currencies is a programming error`() {
        shouldThrow<IllegalArgumentException> { Money(1, eur) + Money(1, sek) }
        shouldThrow<IllegalArgumentException> { Money(1, eur) - Money(1, sek) }
    }

    @Test
    fun `overflow throws instead of wrapping`() {
        shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE, eur) + Money(1, eur) }
        shouldThrow<ArithmeticException> { Money(Long.MIN_VALUE, eur) - Money(1, eur) }
        shouldThrow<ArithmeticException> { -Money(Long.MIN_VALUE, eur) }
    }

    @Test
    fun `formats with the currency's minor-unit digits`() {
        Money(1234, eur).toPlainString() shouldBe "12.34"
        Money(-5, eur).toPlainString() shouldBe "-0.05"
        Money(0, eur).toPlainString() shouldBe "0.00"
        Money(100, CurrencyCode("JPY")).toPlainString() shouldBe "100"
        Money(1234, CurrencyCode("BHD")).toPlainString() shouldBe "1.234"
        Money(5000, eur).toString() shouldBe "50.00 EUR"
    }

    @Test
    fun `MAX_MINOR is ten to the thirteenth`() {
        MAX_MINOR shouldBe 10_000_000_000_000L
    }

    @Test
    fun `zero has no minor units`() {
        Money.zero(eur) shouldBe Money(0, eur)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.money.*"
```

Expected: compilation FAILS with `Unresolved reference 'CurrencyCode'` and `Unresolved reference 'Money'`.

- [ ] **Step 3: Implement `CurrencyCode` and `Money`**

`android/app/src/main/kotlin/io/github/codex07/iou/money/CurrencyCode.kt`:

```kotlin
package io.github.codex07.iou.money

import java.util.Currency

/**
 * An ISO 4217 currency code that `java.util.Currency` knows and that has minor units.
 * Codes without minor units (precious metals, `XXX`, …) are not money in this app.
 */
@JvmInline
value class CurrencyCode(val code: String) {

    init {
        require(isSupported(code)) { "Unsupported currency code: $code" }
    }

    val fractionDigits: Int
        get() = Currency.getInstance(code).defaultFractionDigits

    override fun toString(): String = code

    companion object {
        /** Lenient parse for user and file input: trims and uppercases; null when unsupported. */
        fun parse(text: String): CurrencyCode? {
            val code = text.trim().uppercase()
            return if (isSupported(code)) CurrencyCode(code) else null
        }

        private fun isSupported(code: String): Boolean {
            if (code.length != 3 || !code.all { it in 'A'..'Z' }) return false
            val currency = runCatching { Currency.getInstance(code) }.getOrNull() ?: return false
            return currency.defaultFractionDigits >= 0
        }
    }
}
```

`android/app/src/main/kotlin/io/github/codex07/iou/money/Money.kt`:

```kotlin
package io.github.codex07.iou.money

import java.math.BigDecimal

/** Largest amount any stored money value may have, in minor units (spec §3.1). */
const val MAX_MINOR: Long = 10_000_000_000_000L

/**
 * An amount in minor units of one currency. Arithmetic is exact: an overflow throws
 * [ArithmeticException], which the write path reports as `AmountTooLarge`.
 */
data class Money(val amountMinor: Long, val currency: CurrencyCode) {

    operator fun plus(other: Money): Money =
        Money(Math.addExact(amountMinor, sameCurrency(other).amountMinor), currency)

    operator fun minus(other: Money): Money =
        Money(Math.subtractExact(amountMinor, sameCurrency(other).amountMinor), currency)

    operator fun unaryMinus(): Money = Money(Math.negateExact(amountMinor), currency)

    /** Major units with exactly the currency's minor digits and a `.` separator, e.g. "12.34". */
    fun toPlainString(): String =
        BigDecimal.valueOf(amountMinor, currency.fractionDigits).toPlainString()

    override fun toString(): String = "${toPlainString()} ${currency.code}"

    private fun sameCurrency(other: Money): Money {
        require(other.currency == currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return other
    }

    companion object {
        fun zero(currency: CurrencyCode): Money = Money(0, currency)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.money.*"
```

Expected: `BUILD SUCCESSFUL`; `CurrencyCodeTest` (5 tests) and `MoneyTest` (6 tests) pass. The report is at `app/build/reports/tests/testFakeDebugUnitTest/index.html`.

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/money android/app/src/test/kotlin/io/github/codex07/iou/money
git commit -m "Add CurrencyCode and Money with exact arithmetic"
```

---

### Task 3: `MoneyParser`

**Files:**
- Create: `android/app/src/main/kotlin/io/github/codex07/iou/money/MoneyParser.kt`
- Test: `android/app/src/test/kotlin/io/github/codex07/iou/money/MoneyParserTest.kt`

**Interfaces:**
- Consumes: `CurrencyCode`, `Money`, `MAX_MINOR` (Task 2).
- Produces:
  - `sealed interface MoneyError` with `data object InvalidAmount`, `data class TooManyDecimals(val maxDecimals: Int)`, `data object AmountTooLarge`.
  - `sealed interface MoneyParseResult` with `data class Ok(val money: Money)`, `data class Err(val error: MoneyError)`.
  - `object MoneyParser { fun parse(text: String, currency: CurrencyCode): MoneyParseResult }`. Accepts a non-negative plain decimal with `.` or `,` as the decimal separator; no signs, exponents or grouping. Part 2 maps `MoneyError` 1:1 onto `LedgerError.InvalidAmount` / `TooManyDecimals` / `AmountTooLarge`.

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/kotlin/io/github/codex07/iou/money/MoneyParserTest.kt`:

```kotlin
package io.github.codex07.iou.money

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MoneyParserTest {

    private val eur = CurrencyCode("EUR")
    private val jpy = CurrencyCode("JPY")
    private val bhd = CurrencyCode("BHD")

    private fun ok(minor: Long, currency: CurrencyCode) = MoneyParseResult.Ok(Money(minor, currency))
    private fun err(error: MoneyError) = MoneyParseResult.Err(error)

    @Test
    fun `parses decimals written with a dot or a comma`() {
        MoneyParser.parse("12.34", eur) shouldBe ok(1234, eur)
        MoneyParser.parse("12,34", eur) shouldBe ok(1234, eur)
        MoneyParser.parse(" 12 ", eur) shouldBe ok(1200, eur)
        MoneyParser.parse("0.5", eur) shouldBe ok(50, eur)
        MoneyParser.parse("0", eur) shouldBe ok(0, eur)
        MoneyParser.parse("0.000", eur) shouldBe ok(0, eur)
        MoneyParser.parse("1.234", bhd) shouldBe ok(1234, bhd)
    }

    @Test
    fun `trailing zeros beyond the minor digits are accepted`() {
        MoneyParser.parse("12.340", eur) shouldBe ok(1234, eur)
        MoneyParser.parse("100.00", jpy) shouldBe ok(100, jpy)
    }

    @Test
    fun `more significant decimals than the currency has are rejected`() {
        MoneyParser.parse("1.5", jpy) shouldBe err(MoneyError.TooManyDecimals(0))
        MoneyParser.parse("0.001", eur) shouldBe err(MoneyError.TooManyDecimals(2))
        MoneyParser.parse("1.2345", bhd) shouldBe err(MoneyError.TooManyDecimals(3))
    }

    @Test
    fun `anything but a plain non-negative decimal is invalid`() {
        listOf("", " ", "-5", "+5", "abc", "1e3", "1.2.3", "1 000", ".5", "5.", "1,000.00", "١٢")
            .forEach { text -> MoneyParser.parse(text, eur) shouldBe err(MoneyError.InvalidAmount) }
    }

    @Test
    fun `MAX_MINOR is the largest accepted amount`() {
        MoneyParser.parse("100000000000.00", eur) shouldBe ok(MAX_MINOR, eur)
        MoneyParser.parse("100000000000.01", eur) shouldBe err(MoneyError.AmountTooLarge)
        MoneyParser.parse("10000000000000", jpy) shouldBe ok(MAX_MINOR, jpy)
        MoneyParser.parse("10000000000001", jpy) shouldBe err(MoneyError.AmountTooLarge)
        MoneyParser.parse("99999999999999999999999999", eur) shouldBe err(MoneyError.AmountTooLarge)
    }

    @Test
    fun `parsing a formatted amount gives the same money back`() = runTest {
        checkAll(Arb.long(0L..MAX_MINOR), Arb.element(eur, jpy, bhd)) { minor, currency ->
            val money = Money(minor, currency)
            MoneyParser.parse(money.toPlainString(), currency) shouldBe MoneyParseResult.Ok(money)
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.money.MoneyParserTest"
```

Expected: compilation FAILS with `Unresolved reference 'MoneyParser'`.

- [ ] **Step 3: Implement the parser**

`android/app/src/main/kotlin/io/github/codex07/iou/money/MoneyParser.kt`:

```kotlin
package io.github.codex07.iou.money

import java.math.BigDecimal

sealed interface MoneyError {
    data object InvalidAmount : MoneyError
    data class TooManyDecimals(val maxDecimals: Int) : MoneyError
    data object AmountTooLarge : MoneyError
}

sealed interface MoneyParseResult {
    data class Ok(val money: Money) : MoneyParseResult
    data class Err(val error: MoneyError) : MoneyParseResult
}

/** Text → [Money] through [BigDecimal]; never through floating point (spec §3.1). */
object MoneyParser {

    // ASCII digits only: Java's \d does not match other scripts' digits by default.
    private val plainDecimal = Regex("""^\d+([.,]\d+)?$""")

    fun parse(text: String, currency: CurrencyCode): MoneyParseResult {
        val trimmed = text.trim()
        if (!plainDecimal.matches(trimmed)) return MoneyParseResult.Err(MoneyError.InvalidAmount)

        val parsed = BigDecimal(trimmed.replace(',', '.'))
        // stripTrailingZeros() of a zero keeps its scale on old JDKs, so zero is handled apart.
        val value = if (parsed.signum() == 0) BigDecimal.ZERO else parsed.stripTrailingZeros()

        val digits = currency.fractionDigits
        if (value.scale() > digits) return MoneyParseResult.Err(MoneyError.TooManyDecimals(digits))

        val minor = value.movePointRight(digits)
        if (minor > BigDecimal.valueOf(MAX_MINOR)) return MoneyParseResult.Err(MoneyError.AmountTooLarge)

        return MoneyParseResult.Ok(Money(minor.longValueExact(), currency))
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.money.*"
```

Expected: `BUILD SUCCESSFUL`; all money tests pass (17 tests, including the 1 000-case property test).

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/money/MoneyParser.kt android/app/src/test/kotlin/io/github/codex07/iou/money/MoneyParserTest.kt
git commit -m "Add MoneyParser for exact text-to-money parsing"
```

---

### Task 4: Domain purity check (Android imports, `Double`/`Float`)

**Files:**
- Test: `android/app/src/test/kotlin/io/github/codex07/iou/DomainPurityTest.kt`

**Interfaces:**
- Consumes: the `money` package (Task 2) so that the check has something real to scan.
- Produces: a JVM test that fails CI when (a) a file under `money`, `ledger`, `api` or `fake` imports `android.*`/`androidx.*`, or (b) a file under `money`, `ledger`, `api`, `data` or `io` uses `Double`, `Float`, `.toDouble(`, `.toFloat(` or a floating-point literal outside strings and comments. Packages that don't exist yet are skipped, so later parts are covered automatically when they create them.

- [ ] **Step 1: Write the check and its self-test**

The scanner's own test comes first, so a regex that silently matches nothing can't pass.

`android/app/src/test/kotlin/io/github/codex07/iou/DomainPurityTest.kt`:

```kotlin
package io.github.codex07.iou

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** Spec §2.1 and §11: Android-free domain packages, and no floating point in money paths. */
class DomainPurityTest {

    // Gradle runs Android unit tests with the module directory (android/app) as working directory.
    private val sourceRoot = File("src/main/kotlin/io/github/codex07/iou")

    private val androidFreePackages = listOf("money", "ledger", "api", "fake")
    private val noFloatingPointPackages = listOf("money", "ledger", "api", "data", "io")

    @Test
    fun `the scan runs against the real source tree`() {
        sourceRoot.resolve("money").isDirectory shouldBe true
    }

    @Test
    fun `android-free packages import nothing from Android`() {
        offenders(androidFreePackages, SourceScan::importsAndroid).shouldBeEmpty()
    }

    @Test
    fun `money paths never use Double or Float`() {
        offenders(noFloatingPointPackages, SourceScan::usesFloatingPoint).shouldBeEmpty()
    }

    @Test
    fun `the scanner flags floating point and ignores strings and comments`() {
        listOf(
            "val x: Double = 0",
            "fun f(): Float",
            "val y = 1.5",
            "val z = 2f",
            "val w = amount.toDouble()",
            "val v = 1.0e3",
            "val a = text.toDoubleOrNull()",
            "val b = text.toFloatOrNull()",
            "val c = DoubleArray(3)",
            "val d: FloatArray = arr",
            "texts.map(String::toDouble)",
            "val e = 1_000.5",
            "val g = 1e3",
            "val h = 1E-3",
            "val i = \"\${amount.toDouble()}\"",
            "val j = \"total: \${total * 1.5} EUR\"",
        ).forEach { SourceScan.usesFloatingPoint(it) shouldBe true }

        listOf(
            "val s = \"12.34 Double\"",
            "val t = \"\${amount} is 1.5 Double\"",
            "// 1.5 Double",
            " * Float in KDoc",
            "/* 2f */",
            "Money(1234, eur)",
            "val max = 10_000_000_000_000L",
            "x.version",
            "val mask = 0xFF",
            "for (i in 1..2) {}",
            "val doubled = total.times(2)",
        ).forEach { SourceScan.usesFloatingPoint(it) shouldBe false }
    }

    @Test
    fun `the scanner flags android imports only`() {
        SourceScan.importsAndroid("import android.os.Bundle") shouldBe true
        SourceScan.importsAndroid("import androidx.room.Dao") shouldBe true
        SourceScan.importsAndroid("import java.time.Instant") shouldBe false
        SourceScan.importsAndroid("import io.github.codex07.iou.money.Money") shouldBe false
    }

    private fun offenders(packages: List<String>, isOffending: (String) -> Boolean): List<String> =
        packages
            .map(sourceRoot::resolve)
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (isOffending(line)) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
            }
}

internal object SourceScan {

    private val androidImport = Regex("""^\s*import\s+(android|androidx)\.""")
    private val stringLiteral = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
    private val interpolation = Regex("""\$\{([^{}]*)\}""")
    private val floatingPoint = Regex(
        """\b(Double|Float)(Array)?\b""" +
            """|\bto(Double|Float)(OrNull)?\b""" +
            """|(?<![\w.])\d[\d_]*\.\d[\d_]*([eE][+-]?\d+)?[fF]?\b""" +
            """|(?<![\w.])\d[\d_]*[eE][+-]?\d+[fF]?\b""" +
            """|(?<![\w.])\d[\d_]*[fF]\b""",
    )

    fun importsAndroid(line: String): Boolean = androidImport.containsMatchIn(line)

    fun usesFloatingPoint(line: String): Boolean {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
        // A string's text is ignored, but the expressions interpolated into it run, so they stay in the scan.
        val code = line.replace(stringLiteral) { literal ->
            "\"\"" + interpolation.findAll(literal.value).joinToString("") { " " + it.groupValues[1] }
        }.substringBefore("//")
        return floatingPoint.containsMatchIn(code)
    }
}
```

- [ ] **Step 2: Run it against the clean tree**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.DomainPurityTest"
```

Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 3: Prove it catches a violation**

Create a temporary file `android/app/src/main/kotlin/io/github/codex07/iou/money/PurityProbe.kt`:

```kotlin
package io.github.codex07.iou.money

import android.util.Log

internal val probe = 1.5
```

Run:

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.DomainPurityTest"
```

Expected: FAIL. `android-free packages import nothing from Android` lists `…/money/PurityProbe.kt:3: import android.util.Log`, and `money paths never use Double or Float` lists `…/money/PurityProbe.kt:5: internal val probe = 1.5`.

Delete `PurityProbe.kt`, run the same command again, and expect `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /d/Workspace/IOU-App
git status --short
git add android/app/src/test/kotlin/io/github/codex07/iou/DomainPurityTest.kt
git commit -m "Add the domain purity check for Android imports and floating point"
```

`git status --short` must not list `PurityProbe.kt`.

---

### Task 5: CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the Gradle tasks from Tasks 1–4.
- Produces: a workflow named `CI` that runs on pushes to any branch and on pull requests, and that `release.yml` (Task 6) calls through `workflow_call`. Every later part's tests run in it automatically.

- [ ] **Step 1: Write the workflow**

`.github/workflows/ci.yml`. The workflow token is read-only (`contents: read`). The push filter lists branches only, so tag pushes don't run CI twice (the release workflow calls it instead). `testRealDebugUnitTest` is included because the contract suites will run against each flavor's binding.

```yaml
name: CI

on:
  push:
    branches: ["**"]
  pull_request:
  workflow_call:

permissions:
  contents: read

concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    defaults:
      run:
        working-directory: android
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - uses: gradle/actions/setup-gradle@v4

      - name: Lint, unit tests (incl. domain purity check), debug builds
        run: >
          ./gradlew --no-daemon
          lintFakeDebug
          testFakeDebugUnitTest
          testRealDebugUnitTest
          assembleFakeDebug
          assembleRealDebug

      - name: Upload reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: reports
          path: android/app/build/reports
```

- [ ] **Step 2: Check the whole thing locally first**

```bash
./gradlew lintFakeDebug testFakeDebugUnitTest testRealDebugUnitTest assembleFakeDebug assembleRealDebug
```

Expected: `BUILD SUCCESSFUL`. Lint warnings are fine; lint errors are not. If lint reports an error in the placeholder shell, fix the code rather than disabling the check.

- [ ] **Step 3: Commit, push, and watch the run**

```bash
cd /d/Workspace/IOU-App
git add .github/workflows/ci.yml
git commit -m "Add the CI workflow"
git push origin main
gh run watch --repo Code-X07/IOU-App --exit-status $(gh run list --repo Code-X07/IOU-App --workflow CI --commit "$(git rev-parse HEAD)" --limit 1 --json databaseId --jq '.[0].databaseId')
```

GitHub can take a few seconds to register the run; if the inner `gh run list` prints nothing, run the `gh run watch` line again.

Expected: the `CI` run completes with `✓ build`. If it fails, download the `reports` artifact (`gh run download <run-id> --repo Code-X07/IOU-App`), fix, and push again.

---

### Task 6: Release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: `CI` via `workflow_call` (Task 5); the signing variables read by `android/app/build.gradle.kts` (Task 1): `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `RELEASE_VERSION_NAME`, `RELEASE_VERSION_CODE`.
- Produces: on a `v*` tag, a GitHub Release named after the tag with `iou-<tag>.apk` attached. The workflow token is read-only except in the `release` job, which gets `contents: write` for `gh release create`. A tag containing a hyphen (`v0.0.0-test.1`, `v1.0.0-rc.1`) is published as a pre-release. The release job fails loudly, naming each missing secret, when any of the four signing secrets is not set; the unsigned-APK case also fails, because the copy step looks for the signed file name.

- [ ] **Step 1: Write the workflow**

`.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags: ["v*"]

permissions:
  contents: read

jobs:
  ci:
    uses: ./.github/workflows/ci.yml

  release:
    needs: ci
    permissions:
      contents: write
    runs-on: ubuntu-latest
    timeout-minutes: 30
    defaults:
      run:
        working-directory: android
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - uses: gradle/actions/setup-gradle@v4

      - name: Check the signing secrets and decode the keystore
        env:
          RELEASE_KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
          RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: |
          missing=0
          for name in RELEASE_KEYSTORE_BASE64 RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
            if [ -z "${!name}" ]; then
              echo "::error::Secret $name is not set"
              missing=1
            fi
          done
          if [ "$missing" -ne 0 ]; then
            exit 1
          fi
          echo "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/release.jks"

      - name: Build the signed realRelease APK
        env:
          RELEASE_STORE_FILE: ${{ runner.temp }}/release.jks
          RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
          RELEASE_VERSION_CODE: ${{ github.run_number }}
        run: |
          export RELEASE_VERSION_NAME="${GITHUB_REF_NAME#v}"
          ./gradlew --no-daemon assembleRealRelease

      - name: Verify the signature
        run: |
          BUILD_TOOLS="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
          "$BUILD_TOOLS/apksigner" verify --print-certs app/build/outputs/apk/real/release/app-real-release.apk

      - name: Publish the GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          cp app/build/outputs/apk/real/release/app-real-release.apk "iou-${GITHUB_REF_NAME}.apk"
          args=(--title "IOU ${GITHUB_REF_NAME}" --generate-notes)
          if [[ "$GITHUB_REF_NAME" == *-* ]]; then
            args+=(--prerelease)
          fi
          gh release create "$GITHUB_REF_NAME" "iou-${GITHUB_REF_NAME}.apk" "${args[@]}"
```

`RELEASE_VERSION_CODE` uses the workflow's run number, which only grows, so every release installs over the previous one.

- [ ] **Step 2: Commit, push, and check that GitHub accepted the workflow**

```bash
cd /d/Workspace/IOU-App
git add .github/workflows/release.yml
git commit -m "Add the tag-triggered signed release workflow"
git push origin main
gh workflow list --repo Code-X07/IOU-App
```

Expected: the push triggers `CI` only (no tag yet), and `gh workflow list` shows both `CI` and `Release` as active. A YAML syntax error shows up as a failed run named after the file instead. The release itself is verified in Task 7 once the secrets exist.

---

### Task 7: Release keystore, secrets and a test release

**Files:** none in the repo. The keystore lives outside the repo.

**Interfaces:**
- Consumes: `release.yml` (Task 6).
- Produces: repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; a proven tag-to-release pipeline. The real `v0.0.1` tag is pushed at the end of Part 7.

Steps 1–2 involve passwords: **the project owner runs them by hand**. An agent executing this plan stops here and asks the owner.

- [ ] **Step 1 (owner): Create the keystore outside the repo and back it up**

```bash
mkdir -p /d/Secrets
keytool -genkeypair -v -keystore /d/Secrets/iou-release.jks -alias iou -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=IOU"
```

Use one strong password (PKCS12 keystores use the same password for store and key). Copy `iou-release.jks` and the password to an offline backup (spec §2.4): a lost key means users must uninstall to update.

- [ ] **Step 2 (owner): Store the secrets in GitHub**

```bash
base64 -w0 /d/Secrets/iou-release.jks | gh secret set RELEASE_KEYSTORE_BASE64 --repo Code-X07/IOU-App
gh secret set RELEASE_KEY_ALIAS --repo Code-X07/IOU-App --body iou
gh secret set RELEASE_STORE_PASSWORD --repo Code-X07/IOU-App
gh secret set RELEASE_KEY_PASSWORD --repo Code-X07/IOU-App
gh secret list --repo Code-X07/IOU-App
```

The two password commands prompt for the value. Expected: `gh secret list` shows all four names.

Optional, for local signed builds: create `android/keystore.properties` (ignored by git):

```properties
storeFile=D:/Secrets/iou-release.jks
storePassword=<password>
keyAlias=iou
keyPassword=<password>
```

- [ ] **Step 3: Push a throwaway tag and check the release**

```bash
cd /d/Workspace/IOU-App
git tag v0.0.0-test.1
git push origin v0.0.0-test.1
gh run watch --repo Code-X07/IOU-App --exit-status $(gh run list --repo Code-X07/IOU-App --workflow Release --commit "$(git rev-parse HEAD)" --limit 1 --json databaseId --jq '.[0].databaseId')
gh release view v0.0.0-test.1 --repo Code-X07/IOU-App
```

Expected: the `Release` run passes `ci / build` and `release`; the "Verify the signature" step prints `Signer #1 certificate DN: CN=IOU`; `gh release view` lists the asset `iou-v0.0.0-test.1.apk`.

Optionally install it on the emulator: `gh release download v0.0.0-test.1 --repo Code-X07/IOU-App --dir /d/Workspace/wrapper-tmp` then `adb install -r /d/Workspace/wrapper-tmp/iou-v0.0.0-test.1.apk`. Expected: `Success`, and an "IOU" app that shows `IOU (real): UI goes here`. Uninstall it afterwards (`adb uninstall io.github.codex07.iou`), because the debug build uses a different key and can't install over it.

- [ ] **Step 4 (owner confirms): Remove the test release and tag**

```bash
gh release delete v0.0.0-test.1 --repo Code-X07/IOU-App --cleanup-tag --yes
git tag -d v0.0.0-test.1
```

Expected: `gh release list --repo Code-X07/IOU-App` is empty and `git tag` doesn't list the test tag.

---

## Part 1 done when

- `./gradlew lintFakeDebug testFakeDebugUnitTest testRealDebugUnitTest assembleFakeDebug assembleRealDebug` succeeds locally and in CI.
- `fakeDebug` and `realDebug` install side by side and show the placeholder screen.
- A `v*` tag produces a signed APK on a GitHub Release (proven with the throwaway tag).
- `DomainPurityTest` fails on an Android import or floating point in the checked packages (proven with the probe).

Next: Part 2 (`2026-11-02-m0-02-model-errors-math.md`).
