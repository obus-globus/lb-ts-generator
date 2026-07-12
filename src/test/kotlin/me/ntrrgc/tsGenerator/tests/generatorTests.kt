/*
 * Copyright 2017 Alicia Boya García
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications Copyright 2024-2025 commandblock2
 * Modified portions are licensed under GPLv3:
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package me.ntrrgc.tsGenerator.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import me.commandblock2.tsGenerator.generateNPMPackage
import me.ntrrgc.tsGenerator.ClassTransformer
import me.ntrrgc.tsGenerator.TypeScriptGenerator
import me.ntrrgc.tsGenerator.VoidType
import java.time.Instant
import kotlin.io.path.Path
import kotlin.reflect.KClass

fun assertGeneratedCode(
    klass: KClass<*>,
    expectedOutput: Set<String>,
    mappings: Map<KClass<*>, String> = mapOf(),
    classTransformers: List<ClassTransformer> = listOf(),
    ignoreSuperclasses: Set<KClass<*>> = setOf(),
    voidType: VoidType = VoidType.NULL,
    any: String = """
            class Any {
                equals(other: Any | null): boolean;
                hashCode(): int;
                toString(): string;
            }
        """
) {
    val generator = TypeScriptGenerator(
        listOf(klass), mappings, classTransformers,
        ignoreSuperclasses, intTypeName = "int", voidType = voidType
    )

    val expected = expectedOutput.plus(
        any
    )
        .map(TypeScriptDefinitionFactory::fromCode)
        .toSet()
    val actual = generator.individualDefinitions
        .map(TypeScriptDefinitionFactory::fromCode)
        .toSet()

    actual shouldBe expected
}

fun runModuleGenerationWithoutVerification(
    klass: KClass<*>,
    mappings: Map<KClass<*>, String> = mapOf(),
    classTransformers: List<ClassTransformer> = listOf(),
    ignoreSuperclasses: Set<KClass<*>> = setOf(),
    voidType: VoidType = VoidType.NULL
) {
    try {
        val generator = TypeScriptGenerator(
            listOf(klass), mappings, classTransformers,
            ignoreSuperclasses, intTypeName = "number", voidType = voidType
        )

        val modules = generator.definitionsAsModules

        for (module in modules) {
            println("file: ${module.key}")
            println()
            println("content: ${module.value}")
            println()
        }

        true shouldBe true
    } catch (exception: Exception) {
        exception.printStackTrace()
        throw exception
    }

}

@Suppress("unused")
class Empty

@Suppress("unused")
class ClassWithMember(val a: String)

@Suppress("unused")
class SimpleTypes(
    val aString: String,
    var anInt: Int,
    val aDouble: Double,
    private val privateMember: String
)

@Suppress("unused")
class ClassWithLists(
    val aList: List<String>,
    val anArrayList: ArrayList<String>
)

@Suppress("unused")
class ClassWithArray(
    val items: Array<String>
)

@Suppress("unused")
class Widget(
    val name: String,
    val value: Int
)

@Suppress("unused")
class ClassWithDependencies(
    val widget: Widget
)

@Suppress("unused")
class ClassWithNestedDependencies(
    val widget: Widget,
    val classWithDependencies: ClassWithDependencies
)


@Suppress("unused")
class ClassWithMixedNullables(
    val count: Int,
    val time: Instant?
)

@Suppress("unused")
class ClassWithNullables(
    val widget: Widget?
)

@Suppress("unused")
class ClassWithComplexNullables(
    val maybeWidgets: List<String?>?,
    val maybeWidgetsArray: Array<String?>?
)

@Suppress("unused")
class ClassWithNullableList(
    val strings: List<String>?
)

@Suppress("unused")
open class GenericClass<A, out B, out C : List<Any>>(
    val a: A,
    val b: List<B?>,
    val c: C,
    private val privateMember: A
)

@Suppress("unused")
class ClassWithNestedGenericMembers(val xD: List<List<List<Int>>>, val xDD: Result<Result<Result<Int>>>)

@Suppress("unused")
open class BaseClass(val a: Int)

@Suppress("unused")
class DerivedClass(val b: List<String>) : BaseClass(4)

@Suppress("unused")
class GenericDerivedClass<B>(a: Empty, b: List<B?>, c: ArrayList<String>) :
    GenericClass<Empty, B, ArrayList<String>>(a, b, c, a)

@Suppress("unused")
class ClassWithMethods(
    val propertyMethod: () -> Int,
    val propertyMethodReturnsMightNull: () -> Int?,
    val propertyMethodTakesMightNull: (Int?) -> Unit
) {
    fun regularMethod() = 4
    fun regularMethodReturnsMightNull(): Int? = null
    fun regularMethodTakesMightNull(x: Int?) {}
}

@Suppress("unused")
open class ClassWithMethodsThatReturnsOrTakesFunctionalType(
    val propertyMethodReturnsLambda: () -> (() -> Int),
    private val privatePropertyMethodReturnsLambda: () -> (() -> Int),
    protected val protectedPropertyMethodReturnsLambda: () -> (() -> Int),
    val propertyMethodReturnsLambdaMightNull: () -> (() -> Int)?,
    val propertyMethodTakesLambdaMightNull: ((() -> Int)?) -> Unit,
) {
    fun regularMethod() = propertyMethodReturnsLambda
    fun regularMethodReturnsRegularMethod() =
        ClassWithMethodsThatReturnsOrTakesFunctionalType::regularMethod

    fun regularMethodThatReturnsLambdaMightNull() = null
    fun regularMethodTakesLambdaReturnsMightNull(x: () -> Int?) {}

    private fun privateMethod() = null
    protected fun protectedMethod() = null
}

@Suppress("unused")
abstract class AbstractClass(val concreteKotlinProperty: String) {
    abstract val abstractKotlinProperty: Int
    abstract fun abstractMethod()
    fun concreteMethodInAbstractClass() = 4
}


@Suppress("unused")
enum class Direction {
    North,
    West,
    South,
    East
}

@Suppress("unused")
class ClassWithEnum(val direction: Direction)

@Suppress("unused")
data class DataClass(val prop: String)

@Suppress("unused")
class ClassWithAny(val required: Any, val optional: Any?)

@Suppress("unused")
class ClassWithMap(val values: Map<String, String>)

@Suppress("unused")
class ClassWithEnumMap(val values: Map<Direction, String>)

@Suppress("unused")
class ClassWithInner(val value: String = "") {
    inner class Inner(val innerValue: String = "")
}

//@Suppress("unused")
//class Tests : StringSpec({
//    "handles empty class" {
//        assertGeneratedCode(
//            Empty::class, setOf(
//                """
//class Empty extends Any {
//}
//"""
//            )
//        )
//    }
//
//    "handles classes with a single member" {
//        assertGeneratedCode(
//            ClassWithMember::class, setOf(
//                """
//class ClassWithMember extends Any {
//    a: string;
//}
//"""
//            )
//        )
//    }
//
//    "handles SimpleTypes" {
//        assertGeneratedCode(
//            SimpleTypes::class, setOf(
//                """
//    class SimpleTypes extends Any {
//        aString: string;
//        anInt: int;
//        aDouble: number;
//    }
//    """
//            )
//        )
//    }
//
//    "handles ClassWithLists" {
//        assertGeneratedCode(
//            ClassWithLists::class, setOf(
//                """
//    class ClassWithLists extends Any {
//        aList: string[];
//        anArrayList: string[];
//    }
//    """
//            )
//        )
//    }
//
//    "handles ClassWithArray" {
//        assertGeneratedCode(
//            ClassWithArray::class, setOf(
//                """
//    class ClassWithArray extends Any {
//        items: string[];
//    }
//    """
//            )
//        )
//    }
//
//    val widget = """
//    class Widget extends Any {
//        name: string;
//        value: int;
//    }
//    """
//
//    val classWithDependencies = """
//    class ClassWithDependencies extends Any {
//        widget: Widget;
//    }
//    """
//
//    "handles ClassWithDependencies" {
//        assertGeneratedCode(ClassWithDependencies::class, setOf(classWithDependencies, widget))
//    }
//
//    "handles ClassWithNestedDependencies" {
//        assertGeneratedCode(
//            ClassWithNestedDependencies::class, setOf(
//                """
//    class ClassWithNestedDependencies extends Any {
//        classWithDependencies: ClassWithDependencies;
//        widget: Widget;
//    }
//    """, classWithDependencies, widget
//            )
//        )
//    }
//
//    "handles ClassWithNullables" {
//        assertGeneratedCode(
//            ClassWithNullables::class, setOf(
//                """
//    class ClassWithNullables extends Any {
//        widget: Widget | null;
//    }
//    """, widget
//            )
//        )
//    }
//
//    "handles ClassWithMixedNullables using mapping" {
//        assertGeneratedCode(
//            ClassWithMixedNullables::class, setOf(
//                """
//    class ClassWithMixedNullables extends Any {
//        count: int;
//        time: string | null;
//    }
//    """
//            ), mappings = mapOf(Instant::class to "string")
//        )
//    }
//
//    "handles ClassWithMixedNullables using mapping and VoidTypes" {
//        assertGeneratedCode(
//            ClassWithMixedNullables::class, setOf(
//                """
//    class ClassWithMixedNullables extends Any {
//        count: int;
//        time: string | undefined;
//    }
//    """
//            ), mappings = mapOf(Instant::class to "string"), voidType = VoidType.UNDEFINED,
//            any = """
//    class Any {
//        equals(other: Any | undefined): boolean;
//        hashCode(): int;
//        toString(): string;
//    }
//            """.trimIndent()
//        )
//    }
//
//    "handles ClassWithComplexNullables" {
//        assertGeneratedCode(
//            ClassWithComplexNullables::class, setOf(
//                """
//    class ClassWithComplexNullables extends Any {
//        maybeWidgets: (string | null)[] | null;
//        maybeWidgetsArray: (string | null)[] | null;
//    }
//    """
//            )
//        )
//    }
//
//    "handles ClassWithNullableList" {
//        assertGeneratedCode(
//            ClassWithNullableList::class, setOf(
//                """
//    class ClassWithNullableList extends Any {
//        strings: string[] | null;
//    }
//    """
//            )
//        )
//    }
//
//    "handles GenericClass" {
//        assertGeneratedCode(
//            GenericClass::class, setOf(
//                """
//    class GenericClass<A extends Any | null, B extends Any | null, C extends Any[]> extends Any {
//        a: A;
//        b: (B | null)[];
//        c: C;
//    }
//    """
//            )
//        )
//    }
//
//    val unit = """
//    class Unit extends Any {
//        toString(): string;
//    }
//    """
//// Disabled this test due to Result pulling in too many dependencies
////    "handles ClassWithNestedGenericMembers" {
////        assertGeneratedCode(
////            ClassWithNestedGenericMembers::class, setOf(
////                """
////    class ClassWithNestedGenericMembers {
////        xD: int[][][];
////        xDD: Result<Result<Result<int>>>;
////    }
////    """,
////            )
////        )
////    }
//
//    "handles DerivedClass" {
//        assertGeneratedCode(
//            DerivedClass::class, setOf(
//                """
//    class DerivedClass extends BaseClass {
//        b: string[];
//    }
//    """, """
//    class BaseClass extends Any {
//        a: int;
//    }
//    """
//            )
//        )
//    }
//
//    "handles GenericDerivedClass" {
//        assertGeneratedCode(
//            GenericDerivedClass::class, setOf(
//                """
//    class GenericClass<A extends Any | null, B extends Any | null, C extends Any[]> {
//        a: A;
//        b: (B | null)[];
//        c: C;
//    }
//    """, """
//    class GenericDerivedClass<B extends Any | null> extends GenericClass<Empty, B, string[]> {
//    }
//    """, """
//    class Empty extends Any {
//    }
//    """
//            )
//        )
//    }
//
//    "handles ClassWithMethods" {
//        assertGeneratedCode(
//            ClassWithMethods::class, setOf(
//                """
//    class ClassWithMethods extends Any {
//        propertyMethod: () => int;
//        propertyMethodReturnsMightNull: () => int | null;
//        propertyMethodTakesMightNull: (param0: int | null) => Unit;
//        regularMethod(): int;
//        regularMethodReturnsMightNull(): int | null;
//        regularMethodTakesMightNull(x: int | null): Unit;
//    }
//    """, unit
//            )
//        )
//    }
//
//    "handles ClassWithMethodsThatReturnsOrTakesFunctionalType" {
//        assertGeneratedCode(
//            ClassWithMethodsThatReturnsOrTakesFunctionalType::class, setOf(
//                """
//                    class ClassWithMethodsThatReturnsOrTakesFunctionalType extends Any {
//                        propertyMethodReturnsLambda: () => Function0<int>;
//                        propertyMethodReturnsLambdaMightNull: () => Function0<int> | null;
//                        propertyMethodTakesLambdaMightNull: (param0: Function0<int> | null) => Unit;
//                        regularMethod(): Function0<Function0<int>>;
//                        regularMethodReturnsRegularMethod(): KFunction<ClassWithMethodsThatReturnsOrTakesFunctionalType, Function0<Function0<int>>>;
//                        regularMethodTakesLambdaReturnsMightNull(x: Function0<int | null>): Unit;
//                        regularMethodThatReturnsLambdaMightNull(): Void | null;
//                    }
//                """, """
//                    class Any {
//                        equals(other: any): boolean;
//                        hashCode(): int;
//                        toString(): string;
//                    }
//                """, """
//                    interface Function0<R> extends Function<R> {
//                    }
//                """, """
//                    interface Function<R> {
//                    }
//                """, unit, """
//                    interface KFunction<R> extends KCallable<R>, Function<R> {
//                        isExternal: boolean;
//                        isInfix: boolean;
//                        isInline: boolean;
//                        isOperator: boolean;
//                        isSuspend: boolean;
//                    }
//                """, """
//                    interface KCallable<R> extends KAnnotatedElement {
//                        call(args: any[]): R;
//                        callBy(args: { [key: KParameter]: any }): R;
//                        isAbstract: boolean;
//                        isFinal: boolean;
//                        isOpen: boolean;
//                        isSuspend: boolean;
//                        name: string;
//                        parameters: KParameter[];
//                        returnType: KType;
//                        typeParameters: KTypeParameter[];
//                        visibility: KVisibility | null;
//                    }
//                """, """
//                    interface KAnnotatedElement {
//                        annotations: Annotation[];
//                    }
//                """, """
//                    interface Annotation {
//                    }
//                """, """
//                    interface KParameter extends KAnnotatedElement {
//                        index: int;
//                        isOptional: boolean;
//                        isVararg: boolean;
//                        kind: Kind;
//                        name: string | null;
//                        type: KType;
//                    }
//                """, """
//                    type Kind = "INSTANCE" | "EXTENSION_RECEIVER" | "VALUE";
//                """, """
//                    interface KType extends KAnnotatedElement {
//                        arguments: KTypeProjection[];
//                        classifier: KClassifier | null;
//                        isMarkedNullable: boolean;
//                    }
//                """, """
//                    class KTypeProjection extends Any {
//                        component1(): KVariance | null;
//                        component2(): KType | null;
//                        copy(variance: KVariance | null, type: KType | null): KTypeProjection;
//                        equals(other: any): boolean;
//                        hashCode(): int;
//                        toString(): string;
//                        type: KType | null;
//                        variance: KVariance | null;
//                    }
//                """, """
//                    type KVariance = "INVARIANT" | "IN" | "OUT";
//                """, """
//                    interface KClassifier {
//                    }
//                """, """
//                    interface KTypeParameter extends KClassifier {
//                        isReified: boolean;
//                        name: string;
//                        upperBounds: KType[];
//                        variance: KVariance;
//                    }
//                """, """
//                    type KVisibility = "PUBLIC" | "PROTECTED" | "INTERNAL" | "PRIVATE";
//                """, """
//                    class Void {
//                    }
//                """
//            )
//        )
//    }
//
//
//    "handles AbstractClass" {
//        assertGeneratedCode(
//            AbstractClass::class, setOf(
//                """
//    abstract class AbstractClass extends Any {
//        abstractMethod(): Unit;
//        abstractKotlinProperty: int;
//        concreteKotlinProperty: string;
//        concreteMethodInAbstractClass(): int;
//    }
//    """, unit
//            )
//        )
//    }
//
//    "handles ClassWithEnum" {
//        assertGeneratedCode(
//            ClassWithEnum::class, setOf(
//                """
//    class ClassWithEnum extends Any {
//        direction: Direction;
//    }
//    """, """type Direction = "North" | "West" | "South" | "East";"""
//            )
//        )
//    }
//
//    "handles DataClass" {
//        assertGeneratedCode(
//            DataClass::class, setOf(
//                """
//    class DataClass extends Any {
//        component1(): string;
//        copy(prop: string): DataClass;
//        equals(other: Any | null): boolean;
//        hashCode(): int;
//        prop: string;
//        toString(): string;
//    }
//    """
//            )
//        )
//    }
//
//    "handles ClassWithAny" {
//        // Note: in TypeScript any includes null and undefined.
//        assertGeneratedCode(
//            ClassWithAny::class, setOf(
//                """
//    class ClassWithAny extends Any {
//        required: Any;
//        optional: Any | null;
//    }
//    """
//            )
//        )
//    }
//
//    "supports type mapping for classes" {
//        assertGeneratedCode(
//            ClassWithDependencies::class, setOf(
//                """
//class ClassWithDependencies extends Any {
//    widget: CustomWidget;
//}
//"""
//            ), mappings = mapOf(Widget::class to "CustomWidget")
//        )
//    }
//
//    "supports type mapping for basic types" {
//        assertGeneratedCode(
//            DataClass::class, setOf(
//                """
//    class DataClass extends Any {
//        component1(): CustomString;
//        copy(prop: CustomString): DataClass;
//        equals(other: Any | null): boolean;
//        hashCode(): int;
//        prop: CustomString;
//        toString(): CustomString;
//    }
//    """
//            ), mappings = mapOf(String::class to "CustomString"), any = """
//            class Any {
//                equals(other: Any | null): boolean;
//                hashCode(): int;
//                toString(): CustomString;
//            }
//        """
//        )
//    }
//
//    "supports transforming property names" {
//        assertGeneratedCode(
//            DataClass::class, setOf(
//                """
//    class DataClass extends Any {
//        PROP: string;
//        component1(): string;
//        copy(prop: string): DataClass;
//        equals(other: Any | null): boolean;
//        hashCode(): int;
//        toString(): string;
//    }
//    """
//            ), classTransformers = listOf(
//                object : ClassTransformer {
//                    /**
//                     * Returns the property name that will be included in the
//                     * definition.
//                     *
//                     * If it returns null, the value of the next class transformer
//                     * in the pipeline is used.
//                     */
//                    override fun transformPropertyName(
//                        propertyName: String,
//                        property: KProperty<*>,
//                        klass: KClass<*>
//                    ): String {
//                        return propertyName.toUpperCase()
//                    }
//                }
//            ))
//    }
//
//    "supports transforming only some classes" {
//        assertGeneratedCode(
//            ClassWithDependencies::class, setOf(
//                """
//class ClassWithDependencies extends Any {
//    widget: Widget;
//}
//""", """
//class Widget extends Any {
//    NAME: string;
//    VALUE: int;
//}
//"""
//            ), classTransformers = listOf(
//                object : ClassTransformer {
//                    override fun transformPropertyName(
//                        propertyName: String,
//                        property: KProperty<*>,
//                        klass: KClass<*>
//                    ): String {
//                        return propertyName.toUpperCase()
//                    }
//                }.onlyOnSubclassesOf(Widget::class)
//            )
//        )
//    }
//
//    "supports transforming types" {
//        assertGeneratedCode(
//            DataClass::class, setOf(
//                """
//    class DataClass extends Any {
//        component1(): string;
//        copy(prop: string): DataClass;
//        equals(other: Any | null): boolean;
//        hashCode(): int;
//        prop: int | null;
//        toString(): string;
//    }
//    """
//            ), classTransformers = listOf(
//                object : ClassTransformer {
//                    override fun transformPropertyType(type: KType, property: KProperty<*>, klass: KClass<*>): KType {
//                        return if (klass == DataClass::class && property.name == "prop") {
//                            Int::class.createType(nullable = true)
//                        } else {
//                            type
//                        }
//                    }
//                }
//            ))
//    }
//
//    "supports filtering properties" {
//        assertGeneratedCode(
//            SimpleTypes::class, setOf(
//                """
//    class SimpleTypes extends Any {
//        aString: string;
//        aDouble: number;
//    }
//    """
//            ), classTransformers = listOf(
//                object : ClassTransformer {
//                    override fun transformPropertyList(
//                        properties: List<KProperty<*>>,
//                        klass: KClass<*>
//                    ): List<KProperty<*>> {
//                        return properties.filter { it.name != "anInt" }
//                    }
//                }
//            ))
//    }
//
//    "supports filtering subclasses" {
//        assertGeneratedCode(
//            DerivedClass::class, setOf(
//                """
//    class DerivedClass extends BaseClass {
//        B: string[];
//    }
//    """, """
//    class BaseClass extends Any {
//        A: int;
//    }
//    """
//            ), classTransformers = listOf(
//                object : ClassTransformer {
//                    override fun transformPropertyName(
//                        propertyName: String,
//                        property: KProperty<*>,
//                        klass: KClass<*>
//                    ): String {
//                        return propertyName.toUpperCase()
//                    }
//                }.onlyOnSubclassesOf(BaseClass::class)
//            )
//        )
//    }
//
//    "uses all transformers in pipeline" {
//        assertGeneratedCode(
//            SimpleTypes::class, setOf(
//                """
//    class SimpleTypes extends Any {
//        aString12: string;
//        aDouble12: number;
//        anInt12: int;
//    }
//    """
//            ), classTransformers = listOf(
//                object : ClassTransformer {
//                    override fun transformPropertyName(
//                        propertyName: String,
//                        property: KProperty<*>,
//                        klass: KClass<*>
//                    ): String {
//                        return propertyName + "1"
//                    }
//                },
//                object : ClassTransformer {
//                },
//                object : ClassTransformer {
//                    override fun transformPropertyName(
//                        propertyName: String,
//                        property: KProperty<*>,
//                        klass: KClass<*>
//                    ): String {
//                        return propertyName + "2"
//                    }
//                }
//            ))
//    }
//
//    "handles JavaClass" {
//        assertGeneratedCode(
//            JavaClass::class, setOf(
//                """
//    class JavaClass extends Any {
//        finished: boolean;
//        getMultidimensional(): string[][];
//        getName(): string;
//        getResults(): int[];
//        isFinished(): boolean;
//        multidimensional: string[][];
//        name: string;
//        results: int[];
//        setMultidimensional(arg0: string[][]): Unit;
//        setName(arg0: string): Unit;
//        setResults(arg0: int[]): Unit;
//    }
//    """, unit
//            )
//        )
//    }
//
////    "handles JavaClassWithOptional" {
////        assertGeneratedCode(JavaClassWithOptional::class, setOf(
////            """
////    class JavaClassWithOptional {
////        getName(): string;
////        getSurname(): Optional<string>;
////    }
////    """
////        ), classTransformers = listOf(
////            object : ClassTransformer {
////                override fun transformPropertyType(
////                    type: KType,
////                    property: KProperty<*>,
////                    klass: KClass<*>
////                ): KType {
////                    val bean = Introspector.getBeanInfo(klass.java)
////                        .propertyDescriptors
////                        .find { it.name == property.name }
////
////                    val getterReturnType = bean?.readMethod?.kotlinFunction?.returnType
////                    if (getterReturnType?.classifier == Optional::class) {
////                        val wrappedType = getterReturnType.arguments.first().type!!
////                        return wrappedType.withNullability(true)
////                    } else {
////                        return type
////                    }
////                }
////            }
////        ))
////    }
//
//    "handles ClassWithComplexNullables when serializing as undefined" {
//        assertGeneratedCode(
//            ClassWithComplexNullables::class, setOf(
//                """
//    class ClassWithComplexNullables extends Any {
//        maybeWidgets: (string | undefined)[] | undefined;
//        maybeWidgetsArray: (string | undefined)[] | undefined;
//    }
//    """
//            ), voidType = VoidType.UNDEFINED, any = """
//    class Any {
//        equals(other: Any | undefined): boolean;
//        hashCode(): int;
//        toString(): string;
//    }
//            """.trimIndent()
//        )
//    }
//
//    "transforms ClassWithMap" {
//        assertGeneratedCode(
//            ClassWithMap::class, setOf(
//                """
//    class ClassWithMap extends Any {
//        values: { [key: string]: string };
//    }
//    """
//            )
//        )
//    }
//
//    "transforms ClassWithEnumMap" {
//        assertGeneratedCode(
//            ClassWithEnumMap::class, setOf(
//                """
//    type Direction = "North" | "West" | "South" | "East";
//    """, """
//    class ClassWithEnumMap extends Any {
//        values: { [key in Direction]: string };
//    }
//    """
//            )
//        )
//    }
//})


// example from LiquidBounce-NextGen

open class Event

/**
 * A cancellable event
 */
open class CancellableEvent : Event() {

    /**
     * Let you know if the event is cancelled
     *
     * @return state of cancel
     */
    @Suppress("unused")
    var isCancelled: Boolean = false
        private set
    // should generate to a public method: `isCancelled(): boolean`
    // and maybe a private property or don't, both acceptable

    /**
     * Allows you to cancel an event
     */
    @Suppress("unused")
    fun cancelEvent() {
        isCancelled = true
    }

}


@Suppress("unused")
class Example {
    val normalVal: String = "hello"           // Will generate getString()
    var normalVar: Int = 42                   // Will generate getInt() and setInt()

    companion object {
        const val CONSTANT: Int = 1               // Will generate as field
    }

    @JvmField
    var field: Boolean = false      // Will generate as field
    var privateSetVar: Double = 0.0           // should generate a readonly property
        private set
}

@Suppress("unused")
class PacketEvent(val origin: TransferOrigin, val original: Boolean = true) : CancellableEvent()

@Suppress("unused")
enum class TransferOrigin {
    SEND, RECEIVE
}


class ModuleOutput : StringSpec({
    // TODO: re-enable verification when we have a way to test this
    "handles Module Output" {
        runModuleGenerationWithoutVerification(
            ClassWithMethodsThatReturnsOrTakesFunctionalType::class
        )
    }

    "run bean related tests" {
        runModuleGenerationWithoutVerification(
            Example::class
        )
    }

    "run Private Set property" {
        runModuleGenerationWithoutVerification(
            CancellableEvent::class
        )
    }

    "run PacketEven without packet xD" {
        runModuleGenerationWithoutVerification(
            PacketEvent::class
        )
    }

    "run JavaClass" {
        runModuleGenerationWithoutVerification(
            JavaClass::class
        )
    }
})

class Tests : StringSpec({
    "generates NPM package without spitting error" {
        try {
            TypeScriptGenerator(listOf(ClassWithMethodsThatReturnsOrTakesFunctionalType::class, ClassWithInner::class))
                .generateNPMPackage("test-generated-package-types")
                .writePackageTo(Path("./runs"))
        } catch (exception: Exception) {
            exception.printStackTrace()
            throw exception
        }

    }
})

@Suppress("unused")
class ClassWithVararg {
    fun log(prefix: String, vararg values: Any?) {}
    fun noVararg(values: Array<Any?>) {}
    fun midVararg(vararg values: Any?, tail: String) {}
}

class VarargTests : StringSpec({
    "Kotlin vararg emits a TS rest parameter; a real array param does not" {
        assertGeneratedCode(
            ClassWithVararg::class, setOf(
                """
class ClassWithVararg extends Object {
    constructor()
    log(prefix: string, ...values: (Object | null)[]): void;
    midVararg(values: (Object | null)[], tail: string): void;
    noVararg(values: (Object | null)[]): void;
}
"""
            ),
            any = """
                class Object{
                    constructor()
                    equals(other: Object | null): boolean;
                    hashCode(): int;
                    toString(): string;
                }
            """
        )
    }
})
