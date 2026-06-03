package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import me.commandblock2.tsGenerator.commentIfInvalid

// Regression for the malformed `// ; invalid because of -}` output that broke
// parsing of RetryingKt.d.ts / SuspendHandlersKt.d.ts (a JVM inline-class
// @JvmName-mangled member whose '-' makes it invalid TS). The fix must comment
// every line and keep the trailing newline so the following declaration / the
// class's closing brace is not swallowed into the line comment.
class CommentIfInvalidTests : StringSpec({
    "valid declarations pass through unchanged" {
        "valid(): void;\n".commentIfInvalid() shouldBe "valid(): void;\n"
        "    static of(x: string): Tagged;\n".commentIfInvalid() shouldBe
            "    static of(x: string): Tagged;\n"
    }

    "a single-line invalid decl is fully commented and keeps its newline" {
        val out = "    static retrying-NcHsxvU(x: int): Job;\n".commentIfInvalid()
        out shouldBe "// (invalid TS: name contains '-')     static retrying-NcHsxvU(x: int): Job;\n"
    }

    "a multi-line invalid decl comments every line; no token can escape" {
        val input = "    /**\n     * doc\n     */\n    foo-bar(): void;\n"
        val out = input.commentIfInvalid()
        // Every non-blank line is a line comment ...
        out.split("\n").filter { it.isNotBlank() }.all { it.startsWith("//") } shouldBe true
        // ... and the trailing newline survives, so a following `}` stays on
        // its own line rather than being eaten by the comment.
        out.endsWith("\n") shouldBe true
        (out + "}").endsWith("\n}") shouldBe true
    }
})
