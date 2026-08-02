package io.github.nanoforged.sdg

import java.io.File

/**
 * Java 运行时信息（runGame 可执行文件选择与不兼容参数过滤的依据）。
 */
data class JavaRuntime(
    val executable: File,
    val majorVersion: Int?,
    val versionLine: String,
    val isJetBrainsRuntime: Boolean,
)

/**
 * runGame 用 Java 运行时解析：按候选清单顺序探测第一个可用的 java 可执行文件。
 */
interface JavaRuntimeResolver {

    /**
     * 解析可用运行时。
     *
     * @param gameDir 游戏根目录（探测自带 zulu/jre）
     * @param configuredJava 显式指定的 java 可执行文件（`-Pstarsector.javaExec=` / `STARSECTOR_JAVA_EXEC`）
     * @param configuredJavaHomes 显式指定的 JAVA_HOME（`-Pstarsector.javaHome=` / `STARSECTOR_JAVA_HOME` / `JBR17_HOME`）
     * @throws IllegalStateException 所有候选均不可用
     */
    fun resolve(gameDir: File, configuredJava: List<File>, configuredJavaHomes: List<File>): JavaRuntime
}
