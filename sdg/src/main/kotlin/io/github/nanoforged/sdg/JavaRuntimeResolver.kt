package io.github.nanoforged.sdg

import java.io.File

/**
 * Java 运行时信息（runGame 可执行文件选择与不兼容参数过滤的依据）。
 *
 * @property vendor 发行版标识（小写）：zulu / jetbrains / temurin / graalvm / corretto /
 *   microsoft / semeru；无法识别时为 `openjdk`；探测失败信息缺失时为 null。
 */
data class JavaRuntime(
    val executable: File,
    val majorVersion: Int?,
    val versionLine: String,
    val isJetBrainsRuntime: Boolean,
    val vendor: String?,
)

/**
 * runGame 用 Java 运行时解析：按候选清单顺序探测，可按主版本号与 vendor 过滤。
 */
interface JavaRuntimeResolver {

    /**
     * 解析可用运行时。
     *
     * @param gameDir 游戏根目录（探测自带 zulu/jre）
     * @param configuredJava 显式指定的 java 可执行文件（`-Pstarsector.javaExec=` / `STARSECTOR_JAVA_EXEC`）
     * @param configuredJavaHomes 显式指定的 JAVA_HOME（`-Pstarsector.javaHome=` / `STARSECTOR_JAVA_HOME` / `JBR17_HOME`）
     * @param requiredVersion 要求的 Java 主版本号（`-Pstarsector.javaVersion=` / `STARSECTOR_JAVA_VERSION`）；null 不限
     * @param requiredVendor 要求的发行版（`-Pstarsector.javaVendor=` / `STARSECTOR_JAVA_VENDOR`，
     *   如 `zulu` / `jetbrains`）；null 不限
     * @throws IllegalStateException 过滤后无可用候选
     */
    fun resolve(
        gameDir: File,
        configuredJava: List<File>,
        configuredJavaHomes: List<File>,
        requiredVersion: Int? = null,
        requiredVendor: String? = null,
    ): JavaRuntime
}
