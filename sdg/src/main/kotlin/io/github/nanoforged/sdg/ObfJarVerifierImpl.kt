package io.github.nanoforged.sdg

import java.io.File
import java.util.zip.ZipFile

/**
 * [ObfJarVerifier] 默认实现。
 *
 * named 类内部名（如 `com/fs/starfarer/loading/LoadingUtils`）在引用方 class 的常量池中
 * 以 Modified UTF-8 原样存储，因此对 class 条目字节做精确子串匹配即可判定残留。
 * 包限定的长内部名几乎不存在误报面；抽样保证大表（约 22 万条目）下的执行开销可控。
 */
class ObfJarVerifierImpl : ObfJarVerifier {

    override fun verify(obfJar: File, mappingFile: File, sampleSize: Int): List<String> {
        val namedClasses = readNamedClasses(mappingFile)
        require(namedClasses.isNotEmpty()) { "映射表 ${mappingFile.absolutePath} 中没有 named 类条目，无法校验" }

        val stride = maxOf(1, namedClasses.size / sampleSize)
        val sample = namedClasses.filterIndexed { index, _ -> index % stride == 0 }
        val sampleBytes = sample.map { it.toByteArray(Charsets.UTF_8) }

        val violations = mutableListOf<String>()
        ZipFile(obfJar).use { jar ->
            val classEntries = jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
            for (entry in classEntries) {
                val bytes = jar.getInputStream(entry).readBytes()
                sampleBytes.forEachIndexed { index, needle ->
                    if (bytes.containsSubarray(needle)) {
                        violations += "${entry.name} 残留 named 符号 ${sample[index]}"
                    }
                }
            }
        }
        return violations
    }

    /** 读取 tiny v2 三列全量表的 named 类内部名（类行第 4 列，缺省 = 未语义命名，跳过）。 */
    private fun readNamedClasses(mappingFile: File): List<String> =
        mappingFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.startsWith("c\t") }
                .map { it.split('\t') }
                .filter { it.size >= 4 }
                .map { it[3] }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }

    private fun ByteArray.containsSubarray(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            if (this[i] != needle[0]) continue
            for (j in 1 until needle.size) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
