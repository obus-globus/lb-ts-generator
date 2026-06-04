package me.commandblock2.tsGenerator

import kotlin.reflect.KClass

fun KClass<*>.binaryName(): String {
    val fullName = this.java.name
    return fullName.substring(fullName.lastIndexOf('.') + 1)
}

/**
 * If a rendered declaration is invalid TypeScript because it carries a JVM
 * name containing '-' (e.g. an inline/value-class `@JvmName`-mangled member
 * like `retrying-NcHsxvU` or `waitMatchesWithTimeout-WPwdCS8`), comment it out.
 *
 * Every line is commented and the trailing newline is preserved, so the
 * following declaration — or the class's closing `}` — is never swallowed into
 * the line comment. (The previous implementation prefixed only the first line
 * and appended an un-terminated `// ; invalid because of -`, which ate the next
 * token and produced a `TS1005` parse error.)
 */
fun String.commentIfInvalid(): String {
    // Only the DECLARATION can carry an invalid '-' (a JVM @JvmName-mangled
    // member name); a leading TSDoc block legitimately contains hyphens (prose,
    // `* - bullet` lists), so test only the part after the last doc block.
    val declStart = this.lastIndexOf("*/\n").let { if (it >= 0) it + 3 else 0 }
    if (!this.substring(declStart).contains('-')) return this
    val hadTrailingNewline = this.endsWith("\n")
    val commented = this.trimEnd('\n')
        .split("\n")
        .mapIndexed { index, line ->
            if (index == 0) "// (invalid TS: name contains '-') $line" else "// $line"
        }
        .joinToString("\n")
    return commented + (if (hadTrailingNewline) "\n" else "")
}