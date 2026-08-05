package cn.gzuoj.api

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** 文件制品存储的可替换边界。 */
interface ArtifactStore {
    /** 保存内容并返回不包含根目录信息的元数据。 */
    fun put(namespace: String, content: ByteArray, mediaType: String): StoredArtifact

    /** 打开指定存储键的只读数据流。 */
    fun open(storageKey: String): InputStream

    /** 删除尚未写入数据库引用的临时制品。 */
    fun delete(storageKey: String)
}

/** 已保存制品的元数据。 */
data class StoredArtifact(
    /** 相对于存储根目录的安全存储键。 */
    val storageKey: String,
    /** 内容 SHA-256。 */
    val sha256: String,
    /** 内容字节数。 */
    val sizeBytes: Long,
    /** MIME 类型。 */
    val mediaType: String,
)

/** 使用本地文件系统保存制品的首版实现。 */
@Component
class LocalArtifactStore(
    /** 应用配置。 */
    properties: AppProperties,
) : ArtifactStore {
    /** 规范化后的制品根目录。 */
    private val root: Path = properties.artifactRoot.toAbsolutePath().normalize()

    /** 保存制品；先写临时文件，再原子移动到最终位置。 */
    override fun put(namespace: String, content: ByteArray, mediaType: String): StoredArtifact {
        require(namespace.matches(Regex("[a-z0-9-]{1,40}"))) { "制品命名空间不合法" }
        Files.createDirectories(root)
        val sha256 = SecureValues.sha256(content)
        val key = "$namespace/${sha256.take(2)}/${UUID.randomUUID()}"
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".upload-", ".tmp")
        try {
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return StoredArtifact(key, sha256, content.size.toLong(), mediaType)
    }

    /** 打开制品，缺失时返回受控 404。 */
    override fun open(storageKey: String): InputStream {
        val path = resolve(storageKey)
        if (!Files.isRegularFile(path)) {
            throw ApiException(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "制品不存在")
        }
        return Files.newInputStream(path, StandardOpenOption.READ)
    }

    /** 删除指定制品。 */
    override fun delete(storageKey: String) {
        Files.deleteIfExists(resolve(storageKey))
    }

    /** 解析存储键并阻止绝对路径和目录穿越。 */
    private fun resolve(storageKey: String): Path {
        if (storageKey.startsWith('/') || storageKey.contains('\\')) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_STORAGE_KEY", "制品存储键不合法")
        }
        val resolved = root.resolve(storageKey).normalize()
        if (!resolved.startsWith(root)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_STORAGE_KEY", "制品存储键不合法")
        }
        return resolved
    }
}
