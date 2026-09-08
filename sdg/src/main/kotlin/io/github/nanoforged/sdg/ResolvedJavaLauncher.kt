package io.github.nanoforged.sdg

import java.io.File
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher

/**
 * 指向 [JavaRuntime] 探测结果的 [JavaLauncher]。
 *
 * Gradle 9 的 `JavaExec.exec()` 无条件以 `javaLauncher` 为 fork JVM 来源（`executable` 仅参与一致性校验），
 * 而 `JavaToolchainSpec` 不支持按安装路径选择，无法表达 `-Pstarsector.javaExec=` 的显式路径，
 * 因此直接实现公共接口把解析出的运行时注入任务。
 */
class ResolvedJavaLauncher private constructor(
    private val executablePath: RegularFile,
    private val metadata: JavaInstallationMetadata,
) : JavaLauncher {

    override fun getMetadata(): JavaInstallationMetadata = metadata
    override fun getExecutablePath(): RegularFile = executablePath

    private class Metadata(
        private val languageVersion: JavaLanguageVersion,
        private val runtimeVersion: String,
        private val vendor: String,
        private val installationPath: Directory,
    ) : JavaInstallationMetadata {
        override fun getLanguageVersion(): JavaLanguageVersion = languageVersion
        override fun getJavaRuntimeVersion(): String = runtimeVersion
        override fun getJvmVersion(): String = runtimeVersion
        override fun getVendor(): String = vendor
        override fun getInstallationPath(): Directory = installationPath
        override fun isCurrentJvm(): Boolean = false
    }

    companion object {
        /** 由探测结果构建；[runtime] 必须经 [JavaRuntimeResolverImpl.probe] 成功探测（含版本行）。 */
        fun of(objects: ObjectFactory, runtime: JavaRuntime): ResolvedJavaLauncher {
            val versionText = Regex("\"([^\"]+)\"").find(runtime.versionLine)?.groupValues?.get(1)
                ?: runtime.versionLine
            val installation = runtime.executable.parentFile?.parentFile
                ?: throw IllegalStateException("无法定位 Java 安装目录：${runtime.executable}")
            val majorVersion = runtime.majorVersion
                ?: throw IllegalStateException("Java 探测结果缺少主版本号：${runtime.executable} → ${runtime.versionLine}")
            return ResolvedJavaLauncher(
                executablePath = objects.fileProperty().fileValue(runtime.executable).get(),
                metadata = Metadata(
                    languageVersion = JavaLanguageVersion.of(majorVersion),
                    runtimeVersion = versionText,
                    vendor = runtime.vendor ?: "unknown",
                    installationPath = objects.directoryProperty().fileValue(installation).get(),
                ),
            )
        }
    }
}
