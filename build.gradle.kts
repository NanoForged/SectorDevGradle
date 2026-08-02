// 根工程仅承载公共坐标与仓库约定；插件实现见 :sdg 与 :sdg-nanoforge。
allprojects {
    group = "io.github.nanoforged"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        // 消费 NanoForge launch-spec、SourceSector 工具构件（均发布 mavenLocal）
        mavenLocal()
    }
}
