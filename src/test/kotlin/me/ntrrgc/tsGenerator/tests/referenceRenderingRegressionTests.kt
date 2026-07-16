package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import me.ntrrgc.tsGenerator.TypeScriptGenerator

// Path-style self-iterable: iterating the value yields values of the same
// type (java.nio.file.Path : Iterable<Path> is the canonical JDK case).
@Suppress("unused")
class SelfIterableFixture : Iterable<SelfIterableFixture> {
    override fun iterator(): Iterator<SelfIterableFixture> =
        emptyList<SelfIterableFixture>().iterator()
}

@Suppress("unused")
class SelfIterableHolder {
    fun single(): SelfIterableFixture = SelfIterableFixture()
    fun parentOf(p: SelfIterableFixture): SelfIterableFixture = p
}

private fun liveDefinitions(vararg roots: kotlin.reflect.KClass<*>): String {
    val text = TypeScriptGenerator(roots.toList(), intTypeName = "int").definitionsText
    // Commented-out members are invisible to TS - assert on live lines only.
    return text.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
}

class ReferenceRenderingRegressionTests : StringSpec({
    // A REFERENCE to a self-iterable type is one value, not a collection.
    // The old arrayFromKType self branch appended [] and arrayed every such
    // reference in the output (File.toPath(): Path[], Path.getParent():
    // Path[], even Comparable<Path[]>).
    "a reference to a self-iterable type renders nominally, not as an array" {
        val live = liveDefinitions(SelfIterableHolder::class)
        live shouldNotContain "SelfIterableFixture[]"
        live shouldContain "single(): SelfIterableFixture"
        live shouldContain "parentOf(p: SelfIterableFixture): SelfIterableFixture"
    }

    // Enums emit as nominal classes (class X extends Enum<X>), not literal
    // unions, so the old `{ [key in Direction]: V }` mapped type was invalid
    // TS (a class type is not assignable to string|number|symbol - a hard
    // error in the .d.ts under skipLibCheck:false). Enum-keyed maps must take
    // the object-keyed Map<K, V> branch like any other non-primitive key.
    "an enum-keyed map renders as Map<K, V>, not an invalid mapped type" {
        val live = liveDefinitions(ClassWithEnumMap::class)
        live shouldNotContain "[key in"
        live shouldContain "Map<Direction, string>"
    }
})
