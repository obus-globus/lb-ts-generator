package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import me.commandblock2.tsGenerator.generateNPMPackage
import me.ntrrgc.tsGenerator.TypeScriptGenerator
import me.ntrrgc.tsGenerator.VoidType
import me.ntrrgc.tsGenerator.tests.collide_a.Value as ValueA
import me.ntrrgc.tsGenerator.tests.collide_b.Value as ValueB
import java.io.File
import kotlin.io.path.Path

@Suppress("unused")
class UsesBothValues(val fromA: ValueA, val fromB: ValueB)

// W12b: two dependent types with the same simple name must not both import as
// `Value` (TS2300 duplicate identifier). The loser is aliased, at both the
// import and every reference site.
class ImportCollisionTests : StringSpec({
    "colliding simple-name imports are aliased and references disambiguated" {
        val out = File("./runs-collision")
        out.deleteRecursively()
        TypeScriptGenerator(listOf(UsesBothValues::class), intTypeName = "int", voidType = VoidType.NULL)
            .generateNPMPackage("collision-test").writePackageTo(Path(out.path))
        val text = out.walkTopDown().first { it.name == "UsesBothValues.d.ts" }.readText()
        out.deleteRecursively()

        // First claimant keeps the bare name; the collision is aliased.
        text shouldContain "import type { Value } from"
        text shouldContain "import type { Value as Value_2 } from"
        // No duplicate bare `Value` import (would be TS2300).
        Regex("import type \\{ Value \\} from").findAll(text).count() shouldBe 1
        // References use the disambiguated names.
        text shouldContain "fromA: Value"
        text shouldContain "fromB: Value_2"
    }
})
