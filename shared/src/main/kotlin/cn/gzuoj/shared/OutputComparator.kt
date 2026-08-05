package cn.gzuoj.shared

/** OJ 默认文本比较器。 */
object OutputComparator {
    /**
     * 按平台规则规范化文本：统一换行、删除每行行尾空白和文件末尾空行。
     * 行内空白和字符大小写保持不变。
     */
    fun normalize(value: String): String {
        val normalizedLines = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.trimEnd() }
            .toMutableList()

        while (normalizedLines.isNotEmpty() && normalizedLines.last().isEmpty()) {
            normalizedLines.removeLast()
        }
        return normalizedLines.joinToString("\n")
    }

    /** 判断实际输出是否与标准输出等价。 */
    fun matches(expected: String, actual: String): Boolean = normalize(expected) == normalize(actual)
}
