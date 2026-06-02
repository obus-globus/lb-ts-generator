/*
 * Copyright 2025 commandblock2
 *
 * Licensed under the GNU General Public License v3 (GPLv3).
 */

package me.ntrrgc.tsGenerator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Parses the PSI manifest produced by tools/kdoc-extractor and provides
 * TSDoc blocks for FQNs consumed by [TypeScriptGenerator].
 *
 * Thread-safety: read-only after construction; safe for concurrent use.
 */
class KDocSource private constructor(
    private val entries: Map<String, List<Entry>>
) {

    data class Source(val file: String, val line: Int)

    data class Entry(
        val doc: String?,
        val kind: String?,
        val source: Source?,
        val params: Map<String, String>?,
        val returns: String?,
        val deprecated: String?,
        val since: String?,
        val see: List<String>?,
        val sample: String?,
        val authors: List<String>?,
        val anticheat: String?,
        val anticheatVersion: String?,
        val testedOn: String?,
        val notes: List<String>?
    )

    companion object {
        const val MANIFEST_SCHEMA_VERSION: Int = 2

        fun fromJson(json: String): KDocSource {
            val root = JsonParser.parseString(json).asJsonObject
            val schemaVersion = root.get("schemaVersion")?.takeIf { !it.isJsonNull }?.asInt
                ?: throw IllegalStateException("Missing schemaVersion in manifest")
            if (schemaVersion != MANIFEST_SCHEMA_VERSION) {
                throw IllegalStateException(
                    "Manifest schemaVersion $schemaVersion != expected $MANIFEST_SCHEMA_VERSION"
                )
            }
            val entriesObj = root.getAsJsonObject("entries")
                ?: throw IllegalStateException("Missing entries in manifest")

            val index = mutableMapOf<String, List<Entry>>()
            for ((fqn, value) in entriesObj.entrySet()) {
                val entryList = when {
                    value.isJsonArray -> value.asJsonArray.map { parseEntry(it.asJsonObject) }
                    else -> listOf(parseEntry(value.asJsonObject))
                }
                index[fqn] = entryList
            }
            return KDocSource(index)
        }

        fun fromFile(path: String): KDocSource =
            fromJson(File(path).readText(StandardCharsets.UTF_8))

        private fun parseEntry(obj: JsonObject): Entry {
            fun str(key: String) = obj.get(key)?.takeIf { !it.isJsonNull }?.asString
            fun strList(key: String): List<String>? {
                val arr = obj.getAsJsonArray(key) ?: return null
                return arr.map { it.asString }
            }
            fun strMap(key: String): Map<String, String>? {
                val mapObj = obj.getAsJsonObject(key) ?: return null
                return mapObj.entrySet().associate { it.key to it.value.asString }
            }
            val source = obj.getAsJsonObject("source")?.let {
                Source(
                    file = it.get("file")?.asString ?: "",
                    line = it.get("line")?.asInt ?: 0
                )
            }
            return Entry(
                doc = str("doc"),
                kind = str("kind"),
                source = source,
                params = strMap("params"),
                returns = str("returns"),
                deprecated = str("deprecated"),
                since = str("since"),
                see = strList("see"),
                sample = str("sample"),
                authors = strList("authors"),
                anticheat = str("anticheat"),
                anticheatVersion = str("anticheatVersion"),
                testedOn = str("testedOn"),
                notes = strList("notes")
            )
        }
    }

    /** Number of indexed FQNs (used by ts-defgen.js status message). */
    fun size(): Int = entries.size

    /**
     * Returns a TSDoc block for [fqn], or null if no entry exists.
     *
     * For overloads (multiple entries per FQN), the first entry is used.
     * Each line is prefixed with [indent]. The result includes a trailing newline.
     */
    fun tsdocForFqn(fqn: String, indent: String = ""): String? {
        val entry = entries[fqn]?.firstOrNull() ?: return null
        return buildTsdoc(entry, indent)
    }

    // --- rendering helpers --------------------------------------------------

    private val KDOC_LINK_RE = Regex("""\[([A-Za-z_][A-Za-z0-9_.]*)]""")

    private fun kdocLinksToTsdoc(text: String): String =
        KDOC_LINK_RE.replace(text) { mr -> "{@link ${mr.groupValues[1]}}" }

    private fun buildTsdoc(entry: Entry, indent: String): String {
        val lines = mutableListOf<String>()
        lines.add("$indent/**")

        val doc = entry.doc?.let { kdocLinksToTsdoc(it).trimEnd() }
        if (!doc.isNullOrBlank()) {
            for (line in doc.split("\n")) {
                if (line.isNotBlank()) lines.add("$indent * $line")
                else lines.add("$indent *")
            }
        }

        // LB-specific metadata rendered as @remarks (non-standard tags like
        // @anticheat would cause warnings in api-extractor / tsdoc parsers).
        val hasLbMeta = entry.anticheat != null || entry.anticheatVersion != null ||
            entry.testedOn != null || !entry.notes.isNullOrEmpty()
        if (hasLbMeta) {
            if (!doc.isNullOrBlank()) lines.add("$indent *")
            lines.add("$indent * @remarks")
            val acParts = mutableListOf<String>()
            entry.anticheat?.let { acParts.add(kdocLinksToTsdoc(it).trim()) }
            entry.anticheatVersion?.let { acParts.add("(${kdocLinksToTsdoc(it).trim()})") }
            if (acParts.isNotEmpty()) {
                lines.add("$indent * - **Anticheat:** ${acParts.joinToString(" ")}")
            }
            entry.testedOn?.let {
                lines.add("$indent * - **Tested on:** ${kdocLinksToTsdoc(it).trim()}")
            }
            entry.notes?.forEach { note ->
                val clean = kdocLinksToTsdoc(note).replace("\n", " ").trim()
                if (clean.isNotEmpty()) lines.add("$indent * - $clean")
            }
        }

        val hasExtraTags = !entry.params.isNullOrEmpty() || entry.returns != null ||
            entry.deprecated != null || entry.since != null ||
            !entry.see.isNullOrEmpty() || entry.sample != null || !entry.authors.isNullOrEmpty()
        if (hasExtraTags && (!doc.isNullOrBlank() || hasLbMeta)) {
            lines.add("$indent *")
        }

        entry.params?.forEach { (pname, pdoc) ->
            val clean = kdocLinksToTsdoc(pdoc).replace("\n", " ").trim()
            lines.add("$indent * @param $pname $clean".trimEnd())
        }
        entry.returns?.let { lines.add("$indent * @returns ${kdocLinksToTsdoc(it).trim()}") }
        entry.deprecated?.let { lines.add("$indent * @deprecated ${kdocLinksToTsdoc(it).trim()}") }
        entry.since?.let { lines.add("$indent * @since ${it.trim()}") }
        entry.see?.forEach { s -> lines.add("$indent * @see ${kdocLinksToTsdoc(s).trim()}") }
        entry.authors?.forEach { a -> lines.add("$indent * @author ${a.trim()}") }
        entry.sample?.let { s ->
            lines.add("$indent * @example")
            lines.add("$indent * ${kdocLinksToTsdoc(s).trim()}")
        }

        lines.add("$indent */")
        return lines.joinToString("\n") + "\n"
    }
}
