plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.jlink") version "2.26.0"
}

group = "com.ue"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val junitVersion = "5.12.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

application {
    mainModule.set("com.ue.vehiclestickersystem")
    mainClass.set("com.ue.vehiclestickersystem.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics")
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:${junitVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
    implementation("com.mysql:mysql-connector-j:8.3.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

jlink {
    imageZip.set(layout.buildDirectory.file("distributions/app-${javafx.platform.classifier}.zip"))
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    forceMerge("mysql")
    launcher {
        name = "UE-Campus-Pass"
        mainClass.set("com.ue.vehiclestickersystem.Main")
    }
    jpackage {
        installerType = "exe"
        installerName = "UE-Campus-Pass-Setup"
        appVersion = "1.0.0"
        vendor = "University of the East"
        installerOptions = listOf(
            "--win-dir-chooser",
            "--win-shortcut",
            "--win-menu"
        )
    }
}