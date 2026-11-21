package org.json

// A small working stand-in for Android's org.json (parsing + the calls the app uses), so the pure Kotlin
// parts of the app can be tested on a plain JVM. It is NOT part of the app build.

class JSONException(message: String) : Exception(message)

class JSONObject {
    private val map = LinkedHashMap<String, Any?>()

    constructor()

    constructor(json: String) {
        val p = Parser(json)
        val v = p.readValue()
        if (v !is JSONObject) throw JSONException("not an object")
        map.putAll(v.map)
    }

    internal fun raw(): MutableMap<String, Any?> = map

    fun put(name: String, value: Any?): JSONObject { map[name] = value; return this }
    fun put(name: String, value: Int): JSONObject { map[name] = value.toLong(); return this }
    fun put(name: String, value: Long): JSONObject { map[name] = value; return this }
    fun put(name: String, value: Boolean): JSONObject { map[name] = value; return this }

    fun has(name: String): Boolean = map.containsKey(name)
    fun isNull(name: String): Boolean = !map.containsKey(name) || map[name] == null
    fun opt(name: String): Any? = map[name]
    fun keys(): Iterator<String> = map.keys.toList().iterator()

    fun getLong(name: String): Long = (map[name] as? Number)?.toLong() ?: throw JSONException("no long $name")
    fun getString(name: String): String = (map[name] as? String) ?: throw JSONException("no string $name")
    fun optString(name: String): String = optString(name, "")
    fun optString(name: String, fallback: String): String {
        val v = map[name] ?: return fallback
        return if (v is String) v else if (v is Number && v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }
    fun optLong(name: String): Long = optLong(name, 0L)
    fun optLong(name: String, fallback: Long): Long = (map[name] as? Number)?.toLong() ?: fallback
    fun optInt(name: String): Int = optInt(name, 0)
    fun optInt(name: String, fallback: Int): Int = (map[name] as? Number)?.toInt() ?: fallback
    fun optBoolean(name: String): Boolean = optBoolean(name, false)
    fun optBoolean(name: String, fallback: Boolean): Boolean = (map[name] as? Boolean) ?: fallback
    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject
    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray

    override fun toString(): String = Writer.write(this)

    companion object {
        @JvmStatic fun quote(s: String?): String = Writer.str(s ?: "")
    }
}

class JSONArray {
    private val list = ArrayList<Any?>()

    constructor()
    constructor(json: String) {
        val v = Parser(json).readValue()
        if (v !is JSONArray) throw JSONException("not an array")
        list.addAll(v.list)
    }
    constructor(items: Collection<*>) { list.addAll(items) }

    internal fun raw(): MutableList<Any?> = list

    fun length(): Int = list.size
    fun opt(i: Int): Any? = list.getOrNull(i)
    fun put(value: Any?): JSONArray { list.add(value); return this }
    fun getJSONObject(i: Int): JSONObject = (list[i] as? JSONObject) ?: throw JSONException("no object at $i")
    fun getString(i: Int): String = (list[i] as? String) ?: throw JSONException("no string at $i")
    fun getInt(i: Int): Int = (list[i] as? Number)?.toInt() ?: throw JSONException("no int at $i")
    fun optString(i: Int): String { val v = list.getOrNull(i) ?: return ""; return if (v is String) v else v.toString() }
    fun optJSONObject(i: Int): JSONObject? = list.getOrNull(i) as? JSONObject

    override fun toString(): String = Writer.write(this)
}

private object Writer {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is JSONObject -> v.raw().entries.joinToString(",", "{", "}") { str(it.key) + ":" + write(it.value) }
        is JSONArray -> v.raw().joinToString(",", "[", "]") { write(it) }
        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
}

private class Parser(private val s: String) {
    private var i = 0

    fun readValue(): Any? {
        skip()
        if (i >= s.length) throw JSONException("empty")
        return when (val c = s[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> if (c == '-' || c.isDigit()) readNumber() else throw JSONException("unexpected '$c' at $i")
        }
    }

    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun expect(w: String) { if (!s.startsWith(w, i)) throw JSONException("expected $w at $i"); i += w.length }

    private fun readObject(): JSONObject {
        val o = JSONObject()
        i++
        skip()
        if (s[i] == '}') { i++; return o }
        while (true) {
            skip()
            val k = readString()
            skip()
            if (s[i] != ':') throw JSONException("expected : at $i")
            i++
            o.put(k, readValue())
            skip()
            if (s[i] == ',') { i++; continue }
            if (s[i] == '}') { i++; return o }
            throw JSONException("bad object at $i")
        }
    }

    private fun readArray(): JSONArray {
        val a = JSONArray()
        i++
        skip()
        if (s[i] == ']') { i++; return a }
        while (true) {
            a.put(readValue())
            skip()
            if (s[i] == ',') { i++; continue }
            if (s[i] == ']') { i++; return a }
            throw JSONException("bad array at $i")
        }
    }

    private fun readString(): String {
        if (s[i] != '"') throw JSONException("expected string at $i")
        i++
        val sb = StringBuilder()
        while (s[i] != '"') {
            val c = s[i++]
            if (c == '\\') {
                when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(c)
        }
        i++
        return sb.toString()
    }

    private fun readNumber(): Number {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(st, i)
        return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
    }
}
