package com.kanayama.wifiscreen

/** RAOP prefixes the mDNS instance label with a 12-digit MAC and @ (63-byte label limit). */
object ReceiverName {
    fun error(value: String): String? {
        val name = value.trim()
        return when {
            name.isEmpty() -> "请输入设备名称"
            name.any { it.isISOControl() || it == '.' || it == '\\' } -> "名称不能包含换行、句点或反斜线"
            name.toByteArray(Charsets.UTF_8).size > 48 -> "名称过长，请控制在 16 个汉字或 48 个英文字母以内"
            else -> null
        }
    }
    fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
