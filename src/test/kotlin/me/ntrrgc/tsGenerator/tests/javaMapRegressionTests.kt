package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import me.ntrrgc.tsGenerator.TypeScriptGenerator
import kotlin.reflect.KClass

// Kotlin fixtures for A13 (structural JavaMap): every Java/Kotlin Map
// reference renders as the synthetic `JavaMap<K, V>` interface
// (types/JavaMap.d.ts) — the GraalJS host surface of a java.util.Map,
// mirroring the runtime-verified F8 localStorage facade. The three previous
// forms all lied at runtime: index signatures invited bracket access a host
// Map does not honor, and the JS-global `Map<K, V>` named a real lib type
// whose entire API (.set, .size property) is wrong on a host object.
// (Java fixtures — raw Map, 1-arg fastutil shape, Map subtype — in
// A13Fixtures.java.)

@Suppress("unused")
class JavaMapPayload(val x: Int)

@Suppress("unused")
enum class JavaMapKeyEnum { NORTH, SOUTH }

@Suppress("unused", "UNUSED_PARAMETER")
class JavaMapHost {
    val byName: Map<String, JavaMapPayload> = mapOf()
    val byEnum: Map<JavaMapKeyEnum, String> = mapOf()
    val nested: Map<String, Map<String, JavaMapPayload>> = mapOf()
    fun lookup(): Map<String, JavaMapPayload> = mapOf()
    fun store(entries: Map<String, JavaMapPayload>) {}
}

// W12b x A13: a dependent class whose simple name collides with the synthetic
// JavaMap import must lose the name (aliased to JavaMap_2), not TS2300.
@Suppress("unused")
class JavaMap

@Suppress("unused")
class JavaMapNameCollisionHost {
    val theClass: JavaMap? = null
    val theMap: Map<String, String> = mapOf()
}

private fun live(vararg roots: KClass<*>): String =
    TypeScriptGenerator(roots.toList(), intTypeName = "int").definitionsText
        .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

class JavaMapRegressionTests : StringSpec({
    // ---- A13: the three former map forms all render JavaMap ------------------

    "a string-keyed map field renders as JavaMap<string, V>, not an index signature" {
        val out = live(JavaMapHost::class)
        out shouldContain "byName: JavaMap<string, JavaMapPayload>;"
        out shouldNotContain "[key: string]"
    }

    "map return and parameter positions render uniformly" {
        val out = live(JavaMapHost::class)
        out shouldContain "lookup(): JavaMap<string, JavaMapPayload>;"
        out shouldContain "store(entries: JavaMap<string, JavaMapPayload>): void;"
    }

    "an enum-keyed map renders as JavaMap<K, V>, not the JS-global Map" {
        val out = live(JavaMapHost::class)
        out shouldContain "byEnum: JavaMap<JavaMapKeyEnum, string>;"
        out shouldNotContain ": Map<"
    }

    "nested maps recurse naturally" {
        val out = live(JavaMapHost::class)
        out shouldContain "nested: JavaMap<string, JavaMap<string, JavaMapPayload>>;"
    }

    "a raw Map renders as JavaMap (kotlin-reflect raw-bounds the arguments to Object|null)" {
        val out = live(RawMapHolder::class)
        out shouldContain "raw: JavaMap<Object | null, Object | null>;"
        out shouldContain "giveRaw(): JavaMap<Object | null, Object | null>;"
        out shouldNotContain "{ [key: string]: any }"
    }

    "a 1-arg fastutil-shaped map subtype takes the arity fallback" {
        val out = live(OneArgMapHolder::class)
        out shouldContain "byId: JavaMap<any, any>"
        out shouldNotContain "OneArgMap<"
    }

    // ---- A13 x A14: Map subtypes and the statics-only path -------------------

    "a Map subtype renders JavaMap<any, any> at references AND keeps its statics-only module" {
        val out = live(MapSubtypeHolder::class, MapSubtypeFixture::class)
        out shouldContain "sub(): JavaMap<any, any>"
        out shouldContain "class MapSubtypeFixture"
        out shouldContain "static create(): JavaMap<any, any>"
    }

    "the java.util.Map statics-only factories return JavaMap (no more self-shadow of the JS-global)" {
        val gen = TypeScriptGenerator(listOf(java.util.Map::class), intTypeName = "int")
        val moduleText = gen.definitionsAsModules.entries
            .first { it.key.endsWith("java/util/Map.d.ts") }.value
        moduleText shouldContain "JavaMap<K, V>"
        // Depth 2 (java/util/) -> two levels up to the types/ root.
        moduleText shouldContain "import type { JavaMap } from '../../JavaMap.d.ts'"
    }

    // ---- A13: the import crux ------------------------------------------------

    "a module that rendered a map imports JavaMap at the correct relative depth" {
        val gen = TypeScriptGenerator(listOf(JavaMapHost::class), intTypeName = "int")
        val moduleText = gen.definitionsAsModules.entries
            .first { it.key.contains("JavaMapHost") }.value
        // path me/ntrrgc/tsGenerator/tests/JavaMapHost.d.ts -> 4 levels up.
        moduleText shouldContain "import type { JavaMap } from '../../../../JavaMap.d.ts'"
    }

    "types/JavaMap.d.ts is emitted UNCONDITIONALLY (warm-cache imports need the target)" {
        // No map anywhere in the roots — the synthetic module must still exist:
        // ModuleCache-warm moduleTexts carry their JavaMap import verbatim.
        val gen = TypeScriptGenerator(listOf(Empty::class), intTypeName = "int")
        gen.definitionsAsModules.containsKey("JavaMap.d.ts") shouldBe true
        // ...but a module with no map references does not import it.
        val emptyModule = gen.definitionsAsModules.entries
            .first { it.key.contains("Empty") }.value
        emptyModule shouldNotContain "JavaMap"
    }

    // ---- A13: the synthetic module's shape (the F8 facade, typed) ------------

    "JavaMap.d.ts mirrors the F8 facade: V|null lookups, `any` collection views, no entry sibling" {
        val text = TypeScriptGenerator(listOf(Empty::class), intTypeName = "int")
            .definitionsAsModules.getValue("JavaMap.d.ts")
        text shouldContain "import type { Object } from './java/lang/Object.d.ts'"
        text shouldContain "export interface JavaMap<K, V> extends Object {"
        text shouldContain "get(key: K): V | null;"
        text shouldContain "put(key: K, value: V): V | null;"
        text shouldContain "remove(key: K): V | null;"
        text shouldContain "containsKey(key: K): boolean;"
        text shouldContain "size(): number;"
        text shouldContain "isEmpty(): boolean;"
        text shouldContain "forEach(action: (key: K, value: V) => void): void;"
        // GraalJS does NOT array-map host Set/Collection views — `any`, not K[]/V[].
        text shouldContain "keySet(): any;"
        text shouldContain "values(): any;"
        text shouldContain "entrySet(): any;"
        // No index signature, no JS-Map API, no bean-property duals, no $Entry.
        text shouldNotContain "[key:"
        text shouldNotContain "set(key"
        text shouldNotContain "JavaMap\$Entry"
        text shouldNotContain "readonly "
    }

    // ---- W12b x A13: simple-name collision ------------------------------------

    "a dependent class named JavaMap is aliased away from the synthetic import" {
        val gen = TypeScriptGenerator(listOf(JavaMapNameCollisionHost::class), intTypeName = "int")
        val moduleText = gen.definitionsAsModules.entries
            .first { it.key.contains("JavaMapNameCollisionHost") }.value
        moduleText shouldContain "import type { JavaMap as JavaMap_2 } from"
        moduleText shouldContain "theClass: JavaMap_2 | null;"
        moduleText shouldContain "theMap: JavaMap<string, string>;"
    }
})
