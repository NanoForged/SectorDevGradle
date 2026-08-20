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
    // runGame 的 NANOFORGE 启动前置检查链（纯 Java 库，随插件 POM 传递给消费方）
    implementation("io.github.nanoforged:launch-spec:0.1.0-SNAPSHOT")

    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("sdgMod") {
            id = "io.github.nanoforged.sectordevgradle.mod"
            implementationClass = "io.github.nanoforged.sdg.SdgModPlugin"
            displayName = "SectorDevGradle Mod"
            description = "StarSector 模组构建工具链：deobf 工作区、双形态产物（deobf/obf）、部署与运行"
        }
        create("sdgGameDeps") {
            id = "io.github.nanoforged.sectordevgradle.gamedeps"
            implementationClass = "io.github.nanoforged.sdg.SdgGameDepsPlugin"
            displayName = "SectorDevGradle Game Dependencies"
            description = "StarSector 游戏依赖装配：named 仓 4 jar + gameLibraries，可独立 apply 于非 mod 工程"
        }
    }
}
