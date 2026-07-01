package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec

private val OBJECT = """
    class Object{
        constructor()
        equals(other: Object | null): boolean;
        hashCode(): int;
        toString(): string;
    }
"""

// A public primary constructor alongside a private secondary one. When a class
// has any public constructor, the non-public overloads are dropped (TS forbids
// mixed-visibility overloads).
@Suppress("unused")
class MixedCtors(a: Int) {
    private constructor(a: Int, b: Int) : this(a)
}

// A public method overload and a protected one of the same name. TS forbids
// overloads of one name from having mixed visibility, so the protected overload
// is dropped when a public same-name overload exists.
@Suppress("unused")
open class MixedVisibilityMethods {
    fun pick(a: Int): Int = a
    protected fun pick(a: String): String = a
}

class CtorAndRenderingRegressionTests : StringSpec({
    "a private constructor overload is dropped when a public one exists" {
        assertGeneratedCode(
            MixedCtors::class, setOf(
                """
    class MixedCtors extends Object {
        constructor(a: int)
    }
    """
            ),
            any = OBJECT,
        )
    }

    // The TS2675 regression: a Java constructor reports null KVisibility through
    // kotlin-reflect; null must be treated as public, else the class ships a
    // private constructor and subclasses cannot extend it. (This case was only
    // ever caught in regen-validation, never by a unit test.)
    "a Java constructor (null kotlin-reflect visibility) is emitted as public" {
        assertGeneratedCode(
            JavaBaseWithCtor::class, setOf(
                """
    class JavaBaseWithCtor extends Object {
        constructor(arg0: int)
    }
    """
            ),
            any = OBJECT,
        )
    }

    "a protected method overload is dropped when a public same-name one exists" {
        assertGeneratedCode(
            MixedVisibilityMethods::class, setOf(
                """
    class MixedVisibilityMethods extends Object {
        constructor()
        pick(a: int): int;
    }
    """
            ),
            any = OBJECT,
        )
    }

    "a generic constructor's type parameter is substituted to its bound" {
        assertGeneratedCode(
            JavaGenericCtor::class, setOf(
                """
    class JavaGenericCtor extends Object {
        constructor(arg0: Object)
    }
    """
            ),
            any = OBJECT,
        )
    }
})
