package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec

@Suppress("unused")
open class OverloadParent {
    open fun foo(a: Int): Int = a
    open fun foo(a: String): String = a
}

@Suppress("unused")
class OverloadChild : OverloadParent() {
    override fun foo(a: Int): Int = a + 1
}

@Suppress("unused")
open class CollapseOverloads {
    // Int and Long both render as `int` in TS — the two overloads collapse to
    // an identical rendered line and must be de-duplicated.
    open fun bar(a: Int): String = ""
    open fun bar(a: Long): String = ""
}

private val OBJ = """
    class Object{
        constructor()
        equals(other: Object | null): boolean;
        hashCode(): int;
        toString(): string;
    }
"""

// W19: a child overriding one overload must re-emit the parent's sibling
// overloads, else it shadows them and becomes non-assignable to the parent.
class OverloadVarianceTests : StringSpec({
    "child re-emits inherited sibling overloads of a redeclared method" {
        assertGeneratedCode(
            OverloadChild::class, setOf(
                """
    class OverloadChild extends OverloadParent {
        constructor()
        foo(a: int): int;
        foo(a: string): string;
    }
    """,
                """
    class OverloadParent extends Object {
        constructor()
        foo(a: int): int;
        foo(a: string): string;
    }
    """,
            ),
            any = OBJ,
        )
    }

    "overloads that collapse to the same rendered TS signature are de-duplicated" {
        assertGeneratedCode(
            CollapseOverloads::class, setOf(
                """
    class CollapseOverloads extends Object {
        constructor()
        bar(a: int): string;
    }
    """
            ),
            any = OBJ,
        )
    }
})
