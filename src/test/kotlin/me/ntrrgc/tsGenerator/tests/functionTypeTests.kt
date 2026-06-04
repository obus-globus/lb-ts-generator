package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec

@Suppress("unused")
class FnTypes(
    val noArg: () -> Int,
    val oneArg: (String) -> Boolean,
    val twoArg: (Int, String) -> Long,
    val nested: (Int) -> (String) -> Boolean,
    val suspendArg: suspend (Int) -> Unit
)

@Suppress("unused")
class GenericFnTypes<T>(
    val consume: (T) -> Unit
)

@Suppress("unused")
class NullableFnType(
    val onCancellation: ((Int) -> Unit)?
)

@Suppress("unused")
class NullableJavaFnType(
    val onCancellation: Runnable?
)

private val OBJECT = """
    class Object{
        constructor()
        equals(other: Object | null): boolean;
        hashCode(): int;
        toString(): string;
    }
"""

// Verifies Kotlin function types render as TS arrows with their real parameter
// and return types (recovered from kType.arguments), instead of the nominal
// `FunctionN<...>` placeholder or `UNKNOWN` for suspend function types.
class FunctionTypeTests : StringSpec({
    "renders Kotlin function types as TS arrows" {
        assertGeneratedCode(
            FnTypes::class, setOf(
                """
    class FnTypes extends Object {
        constructor(noArg: () => int, oneArg: (param0: string) => boolean, twoArg: (param0: int, param1: string) => int, nested: (param0: int) => (param0: string) => boolean, suspendArg: (param0: int) => void)
        readonly nested: (param0: int) => (param0: string) => boolean;
        readonly noArg: () => int;
        readonly oneArg: (param0: string) => boolean;
        readonly suspendArg: (param0: int) => void;
        readonly twoArg: (param0: int, param1: string) => int;
    }
    """
            ),
            any = OBJECT,
        )
    }

    "renders a function type over a generic parameter" {
        assertGeneratedCode(
            GenericFnTypes::class, setOf(
                """
    class GenericFnTypes<T extends Object | number | string | boolean> extends Object {
        constructor(consume: (param0: T) => void)
        readonly consume: (param0: T) => void;
    }
    """
            ),
            any = OBJECT,
        )
    }

    "a nullable function type parenthesizes the arrow before | null" {
        assertGeneratedCode(
            NullableFnType::class, setOf(
                """
    class NullableFnType extends Object {
        constructor(onCancellation: ((param0: int) => void) | null)
        readonly onCancellation: ((param0: int) => void) | null;
    }
    """
            ),
            any = OBJECT,
        )
    }

    "a nullable Java functional interface also parenthesizes before | null" {
        assertGeneratedCode(
            NullableJavaFnType::class, setOf(
                """
    class NullableJavaFnType extends Object {
        constructor(onCancellation: (() => void) | null)
        readonly onCancellation: (() => void) | null;
    }
    """,
                """
    interface Runnable extends Object{
        run(): void;
    }
    """,
            ),
            any = OBJECT,
        )
    }
})
