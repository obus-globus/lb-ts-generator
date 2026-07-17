package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import me.ntrrgc.tsGenerator.ClassTransformer
import me.ntrrgc.tsGenerator.SyntheticKType
import me.ntrrgc.tsGenerator.TypeScriptGenerator
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KType

// Kotlin fixtures for the wave-5 fixes: A11 (@JvmOverloads reduced-arity call
// forms -> optional trailing params) and the A17 emission nits N1/N2/N4
// (Java fixtures in Wave5Fixtures.java).

// A11: the double gate - `radix`/`strict` have BOTH a Kotlin default AND a
// bytecode reduced-arity overload (via @JvmOverloads), so they render `?`.
// `plain.count` has only the default (no JVM overload exists; GraalJS matches
// by arity, so `plain("x")` throws at runtime) and must stay required.
// `sandwich.b` is a non-trailing default: no contiguous trailing run, so all
// params stay required.
@Suppress("unused", "UNUSED_PARAMETER")
class JvmOverloadsFixture {
    @JvmOverloads
    fun parse(s: String, radix: Int = 10, strict: Boolean = false): Int = 0

    fun plain(s: String, count: Int = 1): Int = 0

    @JvmOverloads
    fun sandwich(a: Int, b: Int = 1, c: String): Int = 0
}

// A11 negative control: data-class copy() has all-default params but NO
// @JvmOverloads (only the filtered copy$default bridge exists in bytecode) -
// its params must stay required, byte-identical to before.
@Suppress("unused")
data class CopyFixture(val a: Int, val b: String)

// A11 constructor rider: same mechanism as Color4b's
// `@JvmOverloads constructor(r, g, b, a: Int = 255)`.
@Suppress("unused")
class CtorOverloadsFixture @JvmOverloads constructor(val a: Int, val b: Int = 1)

// A17-N1: a property shadowed by a same-name method (TagEntityEvent.color).
// F7 drops the field; the bean getter dual must be emitted so the property
// value stays reachable in the typed surface.
@Suppress("unused", "UNUSED_PARAMETER")
class PropMethodShadow {
    val color: Int = 1
    fun color(a: Int, b: Int) {}
    val plainProp: Int = 2
}

// A17-N1: mutable variant - getter AND setter duals.
@Suppress("unused", "UNUSED_PARAMETER")
class MutablePropMethodShadow {
    var label: String = ""
    fun label(x: Int) {}
}

// A17-N2: suspend functions must render the JVM truth (trailing Continuation
// param + `any` return), not the uncallable KFunction view.
@Suppress("unused", "UNUSED_PARAMETER", "RedundantSuspendModifier")
open class SuspendFixture {
    open suspend fun s(x: Int): String = ""
    suspend fun u() {}
}

// A17-N4 (synthetic ctor filter): kotlinc mangles a value-class-typed primary
// ctor into an ACC_SYNTHETIC JVM ctor (`(int, DefaultConstructorMarker)`) -
// the only kotlin-reflect-visible ctor of this class maps to it. Uncallable
// from JS (GraalJS arity match fails), so it must be filtered; with zero
// renderable ctors left, the PROTECTED sentinel is emitted (protected, not
// private: `private constructor()` + `extends` is TS2675).
@JvmInline
@Suppress("unused")
value class InlinePoint(val v: Int)

@Suppress("unused")
class SyntheticCtorOnly(val p: InlinePoint)

// A17-N4 (moduleText alias): host whose dependent type gets no emitted module.
@Suppress("unused")
class IgnoredDep {
    val z: Int = 0
}

@Suppress("unused")
class IgnoredDepUser {
    val dep: IgnoredDep? = null
}

// A17-N4 (guards): host whose property type is rewritten (via transformer) to
// a synthetic hidden class - see the LambdaClassProvider Java fixture.
@Suppress("unused")
class SyntheticRefHost {
    val z: Int = 0
}

private fun live(vararg roots: KClass<*>): String =
    TypeScriptGenerator(roots.toList(), intTypeName = "int").definitionsText
        .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

class Wave5RegressionTests : StringSpec({
    // ---- A11: @JvmOverloads instance methods --------------------------------

    "a @JvmOverloads instance method marks trailing defaulted params optional" {
        live(JvmOverloadsFixture::class) shouldContain
            "parse(s: string, radix?: int, strict?: boolean): int;"
    }

    "a defaulted param WITHOUT @JvmOverloads stays required (no JVM overload exists)" {
        live(JvmOverloadsFixture::class) shouldContain
            "plain(s: string, count: int): int;"
    }

    "a non-trailing default stays required even under @JvmOverloads" {
        live(JvmOverloadsFixture::class) shouldContain
            "sandwich(a: int, b: int, c: string): int;"
    }

    "data-class copy keeps all params required (negative control for the gate)" {
        val out = live(CopyFixture::class)
        out shouldContain "copy(a: int, b: string): CopyFixture;"
        out shouldNotContain "a?: int"
        out shouldNotContain "b?: string"
    }

    // ---- A11: constructor rider ---------------------------------------------

    "a @JvmOverloads constructor marks trailing defaulted params optional" {
        live(CtorOverloadsFixture::class) shouldContain "constructor(a: int, b?: int)"
    }

    // ---- A17-N1: property/method name shadow --------------------------------

    "a property shadowed by a same-name method emits its bean getter dual" {
        val out = live(PropMethodShadow::class)
        val classBlock = out.substringAfter("class PropMethodShadow").substringBefore("\n}")
        classBlock shouldContain "getColor(): int;"
        // The method and the property both still render (F7 resolves the
        // field/method duo downstream by dropping the field).
        classBlock shouldContain "color(a: int, b: int): void;"
        classBlock shouldContain "readonly color: int;"
    }

    "an unshadowed property does NOT gain a getter dual" {
        val out = live(PropMethodShadow::class)
        out shouldNotContain "getPlainProp"
    }

    "a mutable shadowed property emits getter AND setter duals" {
        val out = live(MutablePropMethodShadow::class)
        val classBlock = out.substringAfter("class MutablePropMethodShadow").substringBefore("\n}")
        classBlock shouldContain "getLabel(): string;"
        classBlock shouldContain "setLabel(value: string): void;"
        classBlock shouldContain "label(x: int): void;"
    }

    // ---- A17-N2: suspend functions ------------------------------------------

    "a suspend function renders the JVM truth: Continuation param + any return" {
        val out = live(SuspendFixture::class)
        out shouldContain "s(x: int, \$completion: Continuation<string>): any;"
        // The old lying render must be gone.
        out shouldNotContain "s(x: int): string;"
    }

    "a Unit-returning suspend function renders Continuation<void>" {
        live(SuspendFixture::class) shouldContain
            "u(\$completion: Continuation<void>): any;"
    }

    "the emitted module imports Continuation for suspend signatures" {
        val gen = TypeScriptGenerator(listOf(SuspendFixture::class), intTypeName = "int")
        val moduleText = gen.definitionsAsModules.entries
            .first { it.key.contains("SuspendFixture") }.value
        moduleText shouldContain "import type { Continuation }"
    }

    "a public foo\$suspendImpl static bridge is filtered from the statics" {
        val out = live(SuspendImplLeak::class)
        out shouldNotContain "suspendImpl"
        out shouldContain "static keepMe(): int;"
    }

    // ---- A17-N3: interface statics ------------------------------------------

    "interface static METHODS are not merged into implementing classes" {
        val out = live(WithStaticImpl::class)
        val classBlock = out.substringAfter("class WithStaticImpl").substringBefore("\n}")
        classBlock shouldNotContain "static make"
    }

    "interface CONSTANTS are still inherited by implementers (staticFieldsOf non-regression)" {
        val out = live(WithConstImpl::class)
        val classBlock = out.substringAfter("class WithConstImpl").substringBefore("\n}")
        classBlock shouldContain "static LIMIT: int;"
    }

    // ---- A17-N4: synthetic constructors -------------------------------------

    "synthetic constructors are filtered; an all-synthetic class gets a protected sentinel" {
        val out = live(SyntheticCtorOnly::class)
        val classBlock = out.substringAfter("class SyntheticCtorOnly").substringBefore("\n}")
        classBlock shouldContain "protected constructor()"
        classBlock shouldNotContain "constructor(p"
        classBlock shouldNotContain "DefaultConstructorMarker"
    }

    // ---- A17-N4: anon/synthetic reference guards ----------------------------

    "a synthetic (hidden) class reference renders its supertype or any, never the raw binary name" {
        val hiddenClass = LambdaClassProvider.HOOK.javaClass
        // Fixture positive control: the exact flag combination of javac's ctor
        // access-tag classes - synthetic but neither anonymous nor local.
        hiddenClass.isSynthetic shouldBe true
        hiddenClass.isAnonymousClass shouldBe false
        hiddenClass.isLocalClass shouldBe false

        val transformer = object : ClassTransformer {
            override fun transformPropertyType(type: KType, property: KProperty<*>, klass: KClass<*>): KType =
                if (property.name == "z") SyntheticKType(hiddenClass.kotlin) else type
        }
        val out = TypeScriptGenerator(
            listOf(SyntheticRefHost::class),
            classTransformers = listOf(transformer),
            intTypeName = "int"
        ).definitionsText
        out shouldNotContain "\$\$Lambda"
    }

    // ---- A17-N4: moduleText missing-dependency alias ------------------------

    "a dependent without an emitted module gets a local any-alias instead of a dangling name" {
        val gen = TypeScriptGenerator(
            listOf(IgnoredDepUser::class),
            ignoreSuperclasses = setOf(IgnoredDep::class),
            intTypeName = "int"
        )
        val moduleText = gen.definitionsAsModules.entries
            .first { it.key.contains("IgnoredDepUser") }.value
        // The referenced name still appears in the body...
        moduleText shouldContain "dep: IgnoredDep | null;"
        // ...and now resolves via the local alias instead of dangling (TS2304).
        moduleText shouldContain "type IgnoredDep = any;"
        moduleText shouldNotContain "import type { IgnoredDep }"
    }
})
