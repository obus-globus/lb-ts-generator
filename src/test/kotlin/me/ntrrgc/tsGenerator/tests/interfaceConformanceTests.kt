package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import me.ntrrgc.tsGenerator.TypeScriptGenerator
import me.ntrrgc.tsGenerator.VoidType
import kotlin.reflect.KClass

// B-fix fixtures: a type must structurally satisfy every interface it implements,
// transitively — including abstract interface methods the class does not itself
// declare (the case that broke Java enums / abstract classes and made them
// non-assignable to their interface, e.g. an enum packet type missing
// PacketType.direction()/state()).

@Suppress("unused")
interface IFaceBase {
    fun baseFn(): String
}

@Suppress("unused")
interface IFaceMid : IFaceBase {
    fun midFn(): String
}

// Leaves BOTH interface methods abstract — neither is in its
// declaredMemberFunctions, so without the transitive-closure fix both go missing
// (baseFn is not even on a DIRECT interface).
@Suppress("unused")
abstract class AbstractConformer : IFaceMid

@Suppress("unused")
interface Doer {
    fun doIt(): String
}

// Concretely overrides the interface method, so it is in declaredMemberFunctions.
// The interface-closure pass must NOT re-add it as a duplicate.
@Suppress("unused")
class ConcreteDoer : Doer {
    override fun doIt(): String = ""
}

private fun defOf(klass: KClass<*>, namePrefix: String): String {
    val gen = TypeScriptGenerator(listOf(klass), intTypeName = "int", voidType = VoidType.NULL)
    return gen.individualDefinitions.first { it.trimStart().startsWith(namePrefix) }
}

class InterfaceConformanceTests : StringSpec({
    "abstract class gains direct + transitive abstract interface members (B-fix)" {
        val def = defOf(AbstractConformer::class, "abstract class AbstractConformer")
        def shouldContain "midFn(): string;"   // direct interface, abstract
        def shouldContain "baseFn(): string;"  // transitive interface, abstract
    }

    "concretely-overridden interface method is emitted exactly once" {
        val def = defOf(ConcreteDoer::class, "class ConcreteDoer")
        (def.split("doIt(): string;").size - 1) shouldBe 1
    }

    "java enum gains interface members across a transitive JAVA interface chain" {
        // JEnumImpl implements JMid -> JBase. jMid() is one hop, jBase() is two
        // hops up; the latter is the case the Java-interface walk recovers.
        val def = defOf(JEnumImpl::class, "class JEnumImpl")
        def shouldContain "jMid(): string;"
        def shouldContain "jBase(): string;"
    }
})
