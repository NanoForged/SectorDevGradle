package io.github.nanoforged.sdg

import java.io.File

/**
 * obf 产物校验：reobf 后的 jar 中不应残留 named 命名空间的符号引用。
 *
 * 设计边界（architecture.md 5.4）：obf 产物不提供运行时测试能力，
 * 本校验是唯一的质量门，采用确定性抽样断言。
 */
interface ObfJarVerifier {

    /**
     * 校验 [obfJar] 的 class 条目字节中不残留 [mappingFile] 中的 named 类内部名。
     *
     * @param sampleSize named 类抽样上限（确定性等距抽样）
     * @return 违规明细（"条目 残留符号"），空列表 = 通过
     */
    fun verify(obfJar: File, mappingFile: File, sampleSize: Int): List<String>
}
