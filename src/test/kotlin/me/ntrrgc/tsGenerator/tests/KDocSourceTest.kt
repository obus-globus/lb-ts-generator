/*
 * Copyright 2025 commandblock2
 *
 * Licensed under the GNU General Public License v3 (GPLv3).
 */

package me.ntrrgc.tsGenerator.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import me.ntrrgc.tsGenerator.KDocSource
import java.io.File

class KDocSourceTest : StringSpec({

    // -----------------------------------------------------------------------
    // schemaVersion validation
    // -----------------------------------------------------------------------

    "throws IllegalStateException on wrong schemaVersion" {
        val json = """{"schemaVersion": 99, "entries": {}}"""
        shouldThrow<IllegalStateException> {
            KDocSource.fromJson(json)
        }
    }

    "throws IllegalStateException on missing schemaVersion" {
        val json = """{"entries": {}}"""
        shouldThrow<IllegalStateException> {
            KDocSource.fromJson(json)
        }
    }

    // -----------------------------------------------------------------------
    // Real manifest round-trip
    // -----------------------------------------------------------------------

    val manifestPath = run {
        // Resolve relative to the project root regardless of cwd
        val candidates = listOf(
            // Prefer the real generated manifest when present (helper / typings-repo
            // pipeline); fall back to the in-repo fixture so the standalone repo's
            // tests are self-contained.
            "tools/kdoc-extractor/manifest.json",
            "../kdoc-extractor/manifest.json",
            "src/test/resources/manifest.json"
        )
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("Cannot locate manifest.json — run from the project root or the helper's tools/ts-generator/")
    }

    val source = KDocSource.fromFile(manifestPath.path)

    "manifest loads without exception and has entries" {
        (source.size() > 0) shouldBe true
    }

    "known FQN returns non-null TSDoc block starting with /**" {
        val fqn = "net.ccbluex.liquidbounce.LiquidBounce"
        val doc = source.tsdocForFqn(fqn)
        doc shouldNotBe null
        doc!! shouldStartWith "/**"
    }

    "TSDoc block ends with */ followed by newline" {
        val fqn = "net.ccbluex.liquidbounce.LiquidBounce"
        val doc = source.tsdocForFqn(fqn)!!
        doc shouldContain " */"
        doc.trimEnd() shouldContain "*/"
    }

    "unknown FQN returns null" {
        source.tsdocForFqn("com.example.NonExistentClass") shouldBe null
    }

    "indent param is prepended to all lines" {
        val fqn = "net.ccbluex.liquidbounce.LiquidBounce"
        val indented = source.tsdocForFqn(fqn, "    ")!!
        for (line in indented.trimEnd().split("\n")) {
            line shouldStartWith "    "
        }
    }

    // -----------------------------------------------------------------------
    // KDoc [Symbol] → {@link Symbol} conversion
    // -----------------------------------------------------------------------

    "kdoc [Foo] cross-ref is converted to {@link Foo}" {
        val json = """
            {
              "schemaVersion": 2,
              "entries": {
                "com.example.Foo": {
                  "doc": "See [Foo] and [Bar.baz] for more.",
                  "kind": "class",
                  "source": {"file": "Foo.kt", "line": 1}
                }
              }
            }
        """.trimIndent()
        val src = KDocSource.fromJson(json)
        val doc = src.tsdocForFqn("com.example.Foo")!!
        doc shouldContain "{@link Foo}"
        doc shouldContain "{@link Bar.baz}"
    }

    "original bracket syntax is NOT present after conversion" {
        val json = """
            {
              "schemaVersion": 2,
              "entries": {
                "com.example.Bar": {
                  "doc": "Uses [SomeClass] internally.",
                  "kind": "class",
                  "source": {"file": "Bar.kt", "line": 1}
                }
              }
            }
        """.trimIndent()
        val src = KDocSource.fromJson(json)
        val doc = src.tsdocForFqn("com.example.Bar")!!
        // Raw bracket form must be gone
        (doc.contains("[SomeClass]")) shouldBe false
        doc shouldContain "{@link SomeClass}"
    }

    // -----------------------------------------------------------------------
    // Tag rendering
    // -----------------------------------------------------------------------

    "params, returns, deprecated, since, see are rendered" {
        val json = """
            {
              "schemaVersion": 2,
              "entries": {
                "com.example.Baz.fn": {
                  "doc": "Does a thing.",
                  "kind": "function",
                  "source": {"file": "Baz.kt", "line": 10},
                  "params": {"x": "the x value", "y": "the y value"},
                  "returns": "the result",
                  "deprecated": "use newFn instead",
                  "since": "1.2.3",
                  "see": ["com.example.Other"]
                }
              }
            }
        """.trimIndent()
        val src = KDocSource.fromJson(json)
        val doc = src.tsdocForFqn("com.example.Baz.fn")!!
        doc shouldContain "@param x"
        doc shouldContain "@param y"
        doc shouldContain "@returns the result"
        doc shouldContain "@deprecated use newFn instead"
        doc shouldContain "@since 1.2.3"
        doc shouldContain "@see com.example.Other"
    }

    "overload list — first entry used" {
        val json = """
            {
              "schemaVersion": 2,
              "entries": {
                "com.example.Qux.fn": [
                  {
                    "doc": "First overload.",
                    "kind": "function",
                    "source": {"file": "Qux.kt", "line": 5}
                  },
                  {
                    "doc": "Second overload.",
                    "kind": "function",
                    "source": {"file": "Qux.kt", "line": 10}
                  }
                ]
              }
            }
        """.trimIndent()
        val src = KDocSource.fromJson(json)
        val doc = src.tsdocForFqn("com.example.Qux.fn")!!
        doc shouldContain "First overload."
        (doc.contains("Second overload.")) shouldBe false
    }

    "MANIFEST_SCHEMA_VERSION constant is 2" {
        KDocSource.MANIFEST_SCHEMA_VERSION shouldBe 2
    }
})
