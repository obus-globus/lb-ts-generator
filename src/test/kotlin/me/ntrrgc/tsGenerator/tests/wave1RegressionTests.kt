package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import me.ntrrgc.tsGenerator.TypeScriptGenerator

// Kotlin fixtures for the A-series wave-1 fixes (Java ones in Wave1Fixtures.java).

// A2: BooleanArray was missing from arrayFromKType's primitive table.
@Suppress("unused")
class BooleanArrayHolder {
    fun flags(): BooleanArray = booleanArrayOf()
}

// A3: an implementer does not redeclare an interface's DEFAULT val; the
// emitted class must still carry it (the TS2420 class of debt).
interface HasMeta {
    val meta: String
        get() = "x"
}

@Suppress("unused")
class MetaHolder : HasMeta

// A5: annotation classes are interfaces; kotlin-reflect reports a constructor
// for them, which must not be emitted inside `interface`.
@Suppress("unused")
annotation class MarkerFixture(val id: String)

private fun live(vararg roots: kotlin.reflect.KClass<*>): String =
    TypeScriptGenerator(roots.toList(), intTypeName = "int").definitionsText
        .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

class Wave1RegressionTests : StringSpec({
    // A1 - Comparator SAM: equals(Object) must not win over compare(T,T).
    "Comparator renders as a two-arg comparison arrow, not an equals predicate" {
        val out = live(ComparatorUser::class)
        out shouldContain "(param0: string, param1: string) => int"
        out shouldNotContain "=> boolean"
    }

    // A2 - reference-array Class recursion.
    "a String[] static parameter renders as string[], not (Object | null)[]" {
        val out = live(StaticArrayFixtures::class)
        out shouldContain "main(paramarg0: string[]): void;"
    }

    // A2 - GenericArrayType: arrayness survives erasure; trailing vararg
    // becomes a rest param. (Since A17, the method's own type variable is
    // DECLARED instead of erased, so the element type is T, not Object|null.)
    "a generic vararg keeps its arrayness and rest form" {
        val out = live(StaticArrayFixtures::class)
        out shouldContain "addAll<T extends unknown>(...paramarg0: T[]): void;"
    }

    // A2 - parameterized array element renders through the normal machinery.
    "a List<String>[] parameter renders as string[][]" {
        val out = live(StaticArrayFixtures::class)
        out shouldContain "grids(paramarg0: string[][]): void;"
    }

    // A2 - BooleanArray primitive table entry.
    "a BooleanArray return renders as boolean[]" {
        val out = live(BooleanArrayHolder::class)
        out shouldContain "flags(): boolean[];"
    }

    // A3 - interface default property injection.
    "an interface default val appears on the implementing class" {
        val out = live(MetaHolder::class)
        val classBlock = out.substringAfter("class MetaHolder").substringBefore("}")
        classBlock shouldContain "meta: string"
    }

    // A4 - collapsed overloads emit once.
    "overloads that collapse to one TS signature are deduplicated" {
        val out = live(StaticOverloadCollapse::class)
        out.split("static f(").size - 1 shouldBe 1
    }

    // A4 - shadowed static constants emit once, with the subclass's own type.
    "a shadowing static constant suppresses the inherited duplicate" {
        val out = live(ConstShadow::class)
        val classBlock = out.substringAfter("class ConstShadow").substringBefore("}")
        classBlock.split("static ZERO").size - 1 shouldBe 1
        classBlock shouldContain "static ZERO: int;"
    }

    // A5 - no constructors inside interfaces (annotation classes).
    "an annotation class interface has no constructor" {
        val out = live(MarkerFixture::class)
        val ifaceBlock = out.substringAfter("MarkerFixture").substringBefore("}")
        ifaceBlock shouldNotContain "constructor("
    }
})
