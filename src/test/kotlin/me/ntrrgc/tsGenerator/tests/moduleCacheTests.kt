/*
 * ModuleCache correctness test.
 *
 * Proves the persistent render cache is sound: a "warm" run (cache populated by
 * a prior "cold" run) must produce byte-identical output AND actually reuse
 * modules instead of re-reflecting them.
 *
 * Eligibility is purely content-hash based (own jar + every direct-dep jar + the
 * generator jar unchanged); there is no package allow-list. JDK roots (java.*)
 * are used here only because they're stable and self-contained within the test
 * classpath. The cache dir is passed via the `tsgen.cacheDir` system property so
 * it can be driven in-process.
 */
package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import me.ntrrgc.tsGenerator.TypeScriptGenerator
import java.nio.file.Files

class ModuleCacheTests : StringSpec({

    "warm cache reuses modules and reproduces identical output" {
        val cacheDir = Files.createTempDirectory("tsgen-cache-test").toFile()
        val prop = "tsgen.cacheDir"
        val roots = listOf(
            java.time.DayOfWeek::class,
            java.time.Month::class,
            java.util.concurrent.TimeUnit::class,
            java.math.RoundingMode::class,
        )

        System.setProperty(prop, cacheDir.absolutePath)
        try {
            // Cold run: empty cache -> everything rendered fresh, cache written.
            val cold = TypeScriptGenerator(roots).definitionsAsModules

            // The cold run must have populated the cache on disk.
            java.io.File(cacheDir, "manifest.tsv").exists() shouldBe true
            java.io.File(cacheDir, "raw").isDirectory shouldBe true

            // Warm run: same roots, cache now populated -> the classes
            // are reused (no reflection) and the output must be identical.
            val gen2 = TypeScriptGenerator(roots)
            val warm = gen2.definitionsAsModules

            warm shouldBe cold
            // The four enum roots and their (JDK) dependents are all content-
            // addressable and unchanged -> reused.
            gen2.cacheReuseCount shouldBeGreaterThan 0
        } finally {
            System.clearProperty(prop)
            cacheDir.deleteRecursively()
        }
    }

    "a changed dependency jar invalidates dependents but output stays identical" {
        val cacheDir = Files.createTempDirectory("tsgen-cache-inval").toFile()
        val prop = "tsgen.cacheDir"
        // kotlin.text.Regex lives in kotlin-stdlib (a real jar) and references
        // java.util.regex.* (the JDK, keyed "__jdk__"): a cross-jar dependency.
        val roots = listOf(kotlin.text.Regex::class, kotlin.text.MatchResult::class)

        System.setProperty(prop, cacheDir.absolutePath)
        try {
            // Cold: render + populate cache.
            val cold = TypeScriptGenerator(roots).definitionsAsModules

            // Untampered warm: everything reused.
            val warmClean = TypeScriptGenerator(roots)
            warmClean.definitionsAsModules shouldBe cold
            val cleanReuse = warmClean.cacheReuseCount
            cleanReuse shouldBeGreaterThan 0

            // Tamper the JDK jar's recorded sha: classes whose OWN jar or whose
            // dependency jar is "__jdk__" must now miss and re-render.
            val jarsFile = java.io.File(cacheDir, "jars.tsv")
            val tampered = jarsFile.readLines().joinToString("\n", postfix = "\n") { line ->
                if (line.startsWith("__jdk__\t")) "__jdk__\tTAMPERED" else line
            }
            jarsFile.writeText(tampered)

            // Warm after tampering: re-rendering must reproduce identical output
            // (correctness), but fewer modules are reused (invalidation fired).
            val warmTampered = TypeScriptGenerator(roots)
            warmTampered.definitionsAsModules shouldBe cold
            warmTampered.cacheReuseCount shouldBeLessThan cleanReuse
        } finally {
            System.clearProperty(prop)
            cacheDir.deleteRecursively()
        }
    }
})
