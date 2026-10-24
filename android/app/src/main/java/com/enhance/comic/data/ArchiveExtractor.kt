package com.enhance.comic.data

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * 7z / tar 系列的导入方式：**整包解到磁盘目录**，之后当普通文件夹读。
 *
 * 为什么不按页随机读：7z 默认把整包压成 solid block，取第 200 页要把前面 199 页全解一遍；
 * tar 本身没有索引，是纯流式格式。解一次之后翻页就是读本地文件，零成本。
 * 代价是库里多占一份磁盘，只解图片，跳过说明文件。
 */
object ArchiveExtractor {

    enum class Kind { SEVENZIP, TAR, TAR_GZ, TAR_BZ2, TAR_XZ }

    private val SEVENZIP_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)

    /** 需要「解包成文件夹」的格式；返回 null 表示交给原来的 zip / rar / pdf 路径。 */
    fun kindFor(fileName: String, head: ByteArray): Kind? {
        val lower = fileName.lowercase()

        if (startsWith(head, SEVENZIP_MAGIC)) return Kind.SEVENZIP
        if (startsWith(head, XZ_MAGIC)) return Kind.TAR_XZ
        if (head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) return Kind.TAR_GZ
        if (head.size >= 3 && head[0] == 'B'.code.toByte() &&
            head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte()
        ) {
            return Kind.TAR_BZ2
        }
        // POSIX tar 在第 257 字节写 ustar。
        if (head.size >= 262 && head.copyOfRange(257, 262).toString(Charsets.US_ASCII) == "ustar") {
            return Kind.TAR
        }

        return when {
            lower.endsWith(".7z") || lower.endsWith(".cb7") -> Kind.SEVENZIP
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".cbt.gz") ->
                Kind.TAR_GZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") ->
                Kind.TAR_BZ2
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> Kind.TAR_XZ
            lower.endsWith(".tar") || lower.endsWith(".cbt") -> Kind.TAR
            else -> null
        }
    }

    /**
     * 解压所有图片到 [target]，返回图片数量。目录结构和中文文件名都保留（章节靠它分组）。
     * 非图片条目直接跳过 —— 说明文件、广告图、readme 都不用占空间。
     */
    fun extract(file: File, kind: Kind, target: File): Int {
        target.mkdirs()
        var count = 0
        when (kind) {
            Kind.SEVENZIP -> count = extractSevenZip(file, target)
            else -> count = extractTar(file, kind, target)
        }
        return count
    }

    private fun extractSevenZip(file: File, target: File): Int {
        var count = 0
        val buffer = ByteArray(256 * 1024)
        SevenZFile.builder().setFile(file).get().use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name ?: continue
                if (!ImageUtil.isImageName(name)) continue
                val out = destination(target, name) ?: continue
                out.parentFile?.mkdirs()
                out.outputStream().use { os ->
                    while (true) {
                        val read = archive.read(buffer)
                        if (read < 0) break
                        os.write(buffer, 0, read)
                    }
                }
                count++
            }
        }
        return count
    }

    private fun extractTar(file: File, kind: Kind, target: File): Int {
        var count = 0
        val base: InputStream = BufferedInputStream(file.inputStream())
        val stream: InputStream = when (kind) {
            Kind.TAR -> base
            Kind.TAR_GZ -> GzipCompressorInputStream(base)
            Kind.TAR_BZ2 -> BZip2CompressorInputStream(base)
            Kind.TAR_XZ -> XZCompressorInputStream(base)
            Kind.SEVENZIP -> base
        }
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name ?: continue
                if (!ImageUtil.isImageName(name)) continue
                val out = destination(target, name) ?: continue
                out.parentFile?.mkdirs()
                out.outputStream().use { os -> tar.copyTo(os) }
                count++
            }
        }
        return count
    }

    /** 拒绝绝对路径和 `..`，防止压缩包把文件写到目标目录之外。 */
    private fun destination(target: File, entryName: String): File? {
        val cleaned = entryName.replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty()) return null
        val out = File(target, cleaned)
        val root = target.canonicalPath
        val path = out.canonicalPath
        if (path != root && !path.startsWith(root + File.separator)) return null
        return out
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }
}
