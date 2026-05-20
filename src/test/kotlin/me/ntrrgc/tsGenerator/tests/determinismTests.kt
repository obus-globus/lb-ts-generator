/*
 * Phase 5.3.1a — determinism regression test.
 *
 * Verifies that TypeScriptGenerator produces byte-identical output regardless
 * of the order in which root KClass instances are supplied.  Non-determinism
 * previously surfaced as import-list reordering and method/property shuffle
 * between JVM runs (caused by JVM reflection APIs returning unordered arrays).
 *
 * The test uses a shuffled permutation of the root class list to prove that
 * output is invariant under input permutation.  Running both orderings in a
 * single JVM instance is intentional — the fix must operate on the emitted
 * text (sorted joins), not merely on in-process iteration order.
 */
package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import me.ntrrgc.tsGenerator.TypeScriptGenerator
import kotlin.random.Random

class DeterminismTests : StringSpec({

    "generator output is identical regardless of root class input order" {
        val rootClasses = listOf(
            ClassWithMember::class,
            ClassWithLists::class,
            ClassWithDependencies::class,
            ClassWithNestedDependencies::class,
            ClassWithMixedNullables::class,
            ClassWithMethods::class,
            ClassWithEnum::class,
            DataClass::class,
            ClassWithAny::class,
            AbstractClass::class,
            BaseClass::class,
            DerivedClass::class,
        )

        // Shuffle with a fixed seed so the permutation is deterministic but
        // different from the original declaration order.
        val shuffled = rootClasses.shuffled(Random(42))

        val outputOriginal = TypeScriptGenerator(rootClasses).definitionsAsModules
        val outputShuffled = TypeScriptGenerator(shuffled).definitionsAsModules

        // Both runs should produce the same set of files with the same content.
        outputOriginal.keys shouldBe outputShuffled.keys
        outputOriginal.forEach { (path, content) ->
            content shouldBe outputShuffled[path]
        }
    }
})
