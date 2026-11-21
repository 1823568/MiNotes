import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 从 local.properties 读取第三方服务凭证；该文件已在 .gitignore 中，不会被提交
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// 从 local.properties 读取凭证，源码中不再出现明文密钥
val sparkAppId: String = localProperties.getProperty("SPARK_APP_ID", "")
val sparkApiKey: String = localProperties.getProperty("SPARK_API_KEY", "")
val sparkApiSecret: String = localProperties.getProperty("SPARK_API_SECRET", "")

android {
    namespace = "net.micode.notes"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "net.micode.notes"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SPARK_APP_ID", "\"$sparkAppId\"")
        buildConfigField("String", "SPARK_API_KEY", "\"$sparkApiKey\"")
        buildConfigField("String", "SPARK_API_SECRET", "\"$sparkApiSecret\"")

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

    packaging {
        resources.excludes.add("META-INF/DEPENDENCIES");
        resources.excludes.add("META-INF/NOTICE");
        resources.excludes.add("META-INF/LICENSE");
        resources.excludes.add("META-INF/LICENSE.txt");
        resources.excludes.add("META-INF/NOTICE.txt");
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
//    implementation(fileTree(mapOf(
//        "dir" to "E:\\Code\\Android\\Notesmaster01\\httpcomponents-client-4.5.14-bin\\lib",
//        "include" to listOf("*.aar", "*.jar"),
//        "exclude" to listOf("")
//    )))
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}