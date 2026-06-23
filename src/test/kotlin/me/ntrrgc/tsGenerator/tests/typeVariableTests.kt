package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

@Suppress("unused")
class StringCheckerImpl : JavaGenericChecker<String> {
    override fun test(value: String): Boolean = value.isNotEmpty()
}

@Suppress("unused")
class ErasedCollections {
    fun <T> containsItem(item: T, collection: Collection<T>): Boolean = item in collection
    fun countOf(items: Collection<*>): Int = items.size
}

private val OBJECT = """
    class Object{
        constructor()
        equals(other: Object | null): boolean;
        hashCode(): int;
        toString(): string;
    }
"""

// No emitted member signature may reference an undeclared type variable.
//  - Default methods reflected off a generic interface supertype carry the
//    interface's own type parameters (Predicate<T>-style `T`); a non-generic
//    implementor must substitute the implements-clause type argument.
//  - Iterable-backed array rendering must substitute the collection's element
//    variable (`Collection<out E> : Iterable<E>` yields `E`) with the actual
//    argument, falling back to the variable's bound when unresolvable.
class TypeVariableTests : StringSpec({
    "default methods inherited from a generic interface substitute its type arguments" {
        assertGeneratedCode(
            StringCheckerImpl::class, setOf(
                """
    class StringCheckerImpl extends Object implements JavaGenericChecker<string> {
        constructor()
        and(arg0: (param0: string) => boolean): (param0: string) => boolean;
        test(value: string): boolean;
    }
    """,
                """
    interface JavaGenericChecker<T extends unknown> extends Object{
        and(arg0: (param0: T) => boolean): (param0: T) => boolean;
        test(arg0: T): boolean;
    }
    """,
            ),
            any = OBJECT,
        )
    }

    "type variables nested in collection arguments resolve instead of leaking the element variable" {
        assertGeneratedCode(
            ErasedCollections::class, setOf(
                """
    class ErasedCollections extends Object {
        constructor()
        containsItem<T extends unknown>(item: T, collection: T[]): boolean;
        countOf(items: (Object | null)[]): int;
    }
    """,
            ),
            any = OBJECT,
        )
    }
})

@Suppress("unused")
enum class FBoundedEnum { ALPHA, BETA }

// An F-bounded variable (`T extends Enum<T>`, from java.lang.Enum.valueOf
// reflected onto every enum) must erase to Object, NOT to its raw bound:
// the raw bound gets its missing type arguments padded with Object downstream
// (`Enum<Object>`), which violates the bound's own constraint and emits
// TS2344 at every use site.
class FBoundedErasureTests : StringSpec({
    "F-bounded type variables erase to Object, not a constraint-violating raw bound" {
        val text = me.ntrrgc.tsGenerator.TypeScriptGenerator(
            listOf(FBoundedEnum::class), intTypeName = "int",
        ).definitionsText
        // Commented-out private members are invisible to TS — only live
        // declarations must be free of the constraint-violating instantiation.
        val live = text.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        live shouldNotContain "Enum<Object>"
        live shouldContain "Class<Object>"
    }
})
