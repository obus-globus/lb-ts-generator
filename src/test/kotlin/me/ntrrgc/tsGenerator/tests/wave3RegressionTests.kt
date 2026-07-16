package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import me.ntrrgc.tsGenerator.TypeScriptGenerator

// Kotlin fixtures for the A-series wave-3 fixes (Java ones in Wave3Fixtures.java).

// A12: file-facade (top-level) statics - this test FILE compiles to the
// Wave3RegressionTestsKt facade class; its statics must keep declared Kotlin
// nullability and generics, and an extension receiver must surface as the
// first JVM parameter.
@Suppress("unused")
fun facadeMaybe(x: Int): String? = null

@Suppress("unused")
fun <T> facadeGeneric(x: T): T? = x

@Suppress("unused")
fun StringBuilder.facadeExt(): String? = null

// A12: @JvmStatic bridges on an object keep declared nullability both ways
// (nullable return AND nullable parameter).
@Suppress("unused")
object StaticNullabilityObject {
    @JvmStatic
    fun maybe(s: String): String? = s

    @JvmStatic
    fun sure(s: String?): String = s ?: ""
}

// A12: @JvmStatic bridge on a companion object.
@Suppress("unused")
class StaticNullabilityCompanion {
    companion object {
        @JvmStatic
        fun fromCompanion(i: Int): String? = null
    }
}

// A12 (pair collapse): a nullable interface default + a non-null override
// used to render as two same-parameter overloads; TS picks by declaration
// order, so the non-null one could mask the null. Must collapse to nullable.
interface NullableDefaultIface {
    fun pick(): String? = null
}

@Suppress("unused")
class NullableDefaultImpl : NullableDefaultIface {
    override fun pick(): String = ""
}

// A14: a collection-backed class used to get NO module at all, dropping its
// static factories from the Java.type surface.
@Suppress("unused")
class NoStaticsList : ArrayList<String>()

// A17/A12: enum statics - the class's own valueOf resolves through Kotlin
// metadata; the INHERITED java.lang.Enum.valueOf(Class, String) declares its
// F-bounded generic instead of erasing to `(Class<Object>, string): Object | null`.
@Suppress("unused")
enum class Wave3Enum { LINEAR, EASE }

private fun live(vararg roots: kotlin.reflect.KClass<*>): String =
    TypeScriptGenerator(roots.toList(), intTypeName = "int").definitionsText
        .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

class Wave3RegressionTests : StringSpec({
    // ---- A12: Kotlin static nullability ------------------------------------

    "an object @JvmStatic keeps its declared nullable return" {
        val out = live(StaticNullabilityObject::class)
        out shouldContain "static maybe(s: string): string | null;"
    }

    "an object @JvmStatic keeps its declared nullable parameter" {
        val out = live(StaticNullabilityObject::class)
        out shouldContain "static sure(s: string | null): string;"
    }

    "a companion @JvmStatic keeps its declared nullable return" {
        val out = live(StaticNullabilityCompanion::class)
        val classBlock = out.substringAfter("class StaticNullabilityCompanion ").substringBefore("}")
        classBlock shouldContain "static fromCompanion(i: int): string | null;"
    }

    "a file-facade top-level function keeps nullability, generics and receiver" {
        val facade = Class.forName("me.ntrrgc.tsGenerator.tests.Wave3RegressionTestsKt").kotlin
        val out = live(facade)
        out shouldContain "static facadeMaybe(x: int): string | null;"
        out shouldContain "static facadeGeneric<T extends unknown>(x: T): T | null;"
        // Extension receiver is the first JVM parameter of the static.
        out shouldContain "static facadeExt(self: StringBuilder): string | null;"
    }

    // ---- A12: return-nullability overload-pair collapse ---------------------

    "a nullable interface default and its non-null override collapse to the nullable form" {
        val out = live(NullableDefaultImpl::class)
        val classBlock = out.substringAfter("class NullableDefaultImpl ").substringBefore("}")
        classBlock shouldContain "pick(): string | null;"
        classBlock shouldNotContain "pick(): string;"
    }

    // ---- A17: static method generics ----------------------------------------

    "a generic Java static declares its own type parameter instead of erasing it" {
        val out = live(GenericStaticFixtures::class)
        out shouldContain "static make<T extends unknown>(paramarg0: Class<T>): T;"
        out shouldNotContain "make(paramarg0: Class<Object>"
    }

    "an F-bounded Java static declares the bound instead of a constraint-violating erasure" {
        val out = live(GenericStaticFixtures::class)
        out shouldContain "static pickFirst<T extends Comparable<T>>(paramarg0: T, paramarg1: T): T;"
    }

    // ---- A17: enum statics ---------------------------------------------------

    "enum statics are precise: own valueOf via Kotlin metadata, inherited valueOf declared generic" {
        val out = live(Wave3Enum::class)
        val enumBlock = out.substringAfter("class Wave3Enum ").substringBefore("\n}")
        enumBlock shouldContain "static values(): Wave3Enum[];"
        enumBlock shouldContain "static valueOf(value: string): Wave3Enum;"
        enumBlock shouldContain "static valueOf<T extends Enum<T>>(paramarg0: Class<T>, paramarg1: string): T;"
        enumBlock shouldNotContain "Object | null;"
    }

    // ---- A17: SAM declared on a generic superinterface ----------------------

    "UnaryOperator<T> renders as (T) => T, composing the substitution onto Function" {
        val out = live(UnaryOperatorUser::class)
        out shouldContain "applyOp(arg0: (param0: string) => string): void;"
        out shouldNotContain "=> Object | null): void;"
    }

    // ---- A14: statics-only modules for skipped collection classes -----------

    "a skipped collection interface still yields a statics-only class declaration" {
        val out = live(java.util.List::class)
        out shouldContain "class List<E extends unknown> {"
        // Static factories are reachable...
        out shouldContain "static copyOf<E extends unknown>(paramarg0: E[]): E[];"
        out shouldContain "static of<E extends unknown>(): E[];"
        // ...but no instance surface / constructors are declared (instances
        // keep the structural array rendering).
        val listBlock = out.substringAfter("class List<E extends unknown> {").substringBefore("\n}")
        listBlock shouldNotContain "constructor("
        listBlock.lines().filter { it.isNotBlank() }
            .all { it.trimStart().startsWith("static") || it.trimStart().startsWith("//") } shouldBe true
    }

    "a collection class without statics still emits no module" {
        val out = live(NoStaticsList::class)
        out shouldNotContain "NoStaticsList"
    }

    // ---- A15: nashorn dual surface (Java side) - DISABLED --------------------
    // The Java bean-property dual (readonly title next to getTitle()) is turned
    // off: injecting a bean property onto a base class that is used as a generic
    // bound (Entity) cascaded into +84 TS2344 + TS2416 across the MC hierarchy
    // in a full regen. Only the getter-method form is emitted; a correct dual
    // needs whole-hierarchy analysis. This test locks the disabled state in.
    "the Java bean-property dual is NOT emitted (only the getter method)" {
        val out = live(JavaBeanDual::class)
        val classBlock = out.substringAfter("class JavaBeanDual ").substringBefore("\n}")
        classBlock shouldContain "getTitle(): string;"
        classBlock shouldNotContain "readonly title: string;"
        classBlock shouldNotContain "    mood: string;"
        // A real backing-field property still renders once via the normal path.
        classBlock.split("stash: string;").size - 1 shouldBe 1
    }
})
