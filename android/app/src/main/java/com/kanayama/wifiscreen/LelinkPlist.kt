package com.kanayama.wifiscreen

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Small, bounded XML plist subset used by the v2 mirror control protocol. */
object LelinkPlist {
    fun encode(values: Map<String, Any>): String {
        fun value(item: Any): String = when (item) {
            is String -> "<string>${ReceiverName.xml(item)}</string>"
            is Boolean -> if (item) "<true/>" else "<false/>"
            is Int, is Long -> "<integer>$item</integer>"
            is Double -> "<real>$item</real>"
            is Map<*, *> -> "<dict>" + item.entries.joinToString("") { (k, v) -> "<key>${ReceiverName.xml(k as String)}</key>" + value(v!!) } + "</dict>"
            is List<*> -> "<array>" + item.joinToString("") { value(it!!) } + "</array>"
            else -> throw IOException("Unsupported plist value")
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\">${value(values)}</plist>"
    }

    fun decode(text: String): Map<String, Any> {
        // Xiaomi's serializer emits Apple's public DTD. Strip only that exact declaration;
        // never fetch it, accept an internal subset, or permit another external identifier.
        val safe = text.replace(Regex("<!DOCTYPE\\s+plist\\s+PUBLIC\\s+\"-//apple//DTD PLIST 1.0//EN\"\\s+\"https?://www\\.apple\\.com/DTDs/PropertyList-1\\.0\\.dtd\"\\s*>", RegexOption.IGNORE_CASE), "")
        if (text.length > Rtsp.MAX_BODY || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(safe))
            throw IOException("Unsupported plist document")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw SAXException("External entities forbidden") } }
        val root = builder.parse(InputSource(StringReader(safe))).documentElement
        var nodes = 0
        fun children(element: Element) = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
        fun parse(element: Element, depth: Int): Any {
            if (++nodes > 256 || depth > 12) throw IOException("Plist exceeds nesting limit")
            return when (element.tagName) {
                "dict" -> {
                    val items = children(element)
                    if (items.size % 2 != 0) throw IOException("Malformed plist dictionary")
                    val result = linkedMapOf<String, Any>()
                    for (i in items.indices step 2) {
                        val key = items[i]
                        if (key.tagName != "key" || result.containsKey(key.textContent)) throw IOException("Invalid plist key")
                        result[key.textContent] = parse(items[i + 1], depth + 1)
                    }
                    result
                }
                "array" -> children(element).map { parse(it, depth + 1) }
                "string", "data" -> element.textContent
                "integer" -> element.textContent.trim().toLongOrNull() ?: throw IOException("Invalid plist integer")
                "real" -> element.textContent.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IOException("Invalid plist real")
                "true" -> true
                "false" -> false
                else -> throw IOException("Unsupported plist element")
            }
        }
        if (root.tagName != "plist" || children(root).size != 1) throw IOException("Invalid plist root")
        @Suppress("UNCHECKED_CAST")
        return parse(children(root).single(), 0) as? Map<String, Any> ?: throw IOException("Expected plist dictionary")
    }
}
