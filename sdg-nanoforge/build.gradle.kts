plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.2.21"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(project(":sdg"))

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("sdgNanoForge") {
            id = "io.github.nanoforged.sectordevgradle.nanoforge"
            implementationClass = "io.github.nanoforged.sdg.nanoforge.SdgNanoForgePlugin"
            displayName = "SectorDevGradle NanoForge"
            description = "NanoForge 环境支持：nanoforge.mod.toml、依赖库元数据、coremod 装配、patch 工作流"
        }
    }
}
