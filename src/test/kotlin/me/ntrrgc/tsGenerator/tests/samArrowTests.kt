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

// Java functional interfaces render as TS arrows. The SAM signature comes from
// Java reflection, and every type in it must go through formatKType: boxed
// primitives map to their TS primitives (`Boolean` -> `boolean`, never
// `kotlin.Boolean`) and class references resolve through dependentTypes /
// tsNameFor (simple name + import), never the raw qualified KType.toString().
class SamArrowTests : StringSpec({
    "SAM arrows map boxed primitives and foreign types through formatKType" {
        assertGeneratedCode(
            JavaSamUser::class, setOf(
                """
    class JavaSamUser extends Object {
        constructor()
        combine(arg0: (param0: JavaBox<string>) => boolean): (param0: JavaBox<string>) => boolean;
    }
    """,
                """
    interface BoxedCheck extends Object{
        check(arg0: JavaBox<string>): boolean;
    }
    """,
                """
    class JavaBox<T extends Object | number | string | boolean> extends Object {
        constructor()
        unwrap(): T;
    }
    """,
            ),
            any = OBJECT,
        )
    }

    "a SAM referencing its own interface type falls back to the nominal name" {
        assertGeneratedCode(
            Combiner::class, setOf(
                """
    interface Combiner extends Object{
        combine(arg0: (param0: Combiner) => Combiner): (param0: Combiner) => Combiner;
    }
    """,
            ),
            any = OBJECT,
        )
    }
})
