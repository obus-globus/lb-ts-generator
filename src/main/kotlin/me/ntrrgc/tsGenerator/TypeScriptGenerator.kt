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

package me.ntrrgc.tsGenerator

import me.commandblock2.tsGenerator.binaryName
import me.commandblock2.tsGenerator.commentIfInvalid
import me.commandblock2.tsGenerator.toKFunction
import java.beans.Introspector
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.reflect.*
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaSetter
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.kotlinFunction

/**
 * A17: minimal [KType] implementation for shapes kotlin-reflect cannot build.
 *
 * kotlin-reflect's `createType()` hard-casts every type argument to its own
 * internal `KTypeImpl`, so a type argument backed by [JavaTypeVariableParameter]
 * (a JAVA method-level type variable, which has no kotlin-reflect descriptor)
 * throws. This carries exactly what the rendering path ([formatKType],
 * arrayFromKType, mapFromKType, nonPrimitiveFromKType) consumes:
 * classifier / arguments / nullability.
 */
internal class SyntheticKType(
    override val classifier: KClassifier?,
    override val arguments: List<KTypeProjection> = emptyList(),
    override val isMarkedNullable: Boolean = false
) : KType {
    override val annotations: List<Annotation> = emptyList()
    override fun toString(): String =
        "SyntheticKType($classifier${if (arguments.isEmpty()) "" else arguments.toString()})"
}

/** A12: one rendered member overload, kept structured (instead of a flat
 *  signature string) so return-nullability-contradictory pairs can be
 *  collapsed before emission - see the pair-collapse note in functionsOf. */
internal data class RenderedOverload(
    val name: String,
    val visibility: String,
    val typeParams: String,
    val parameters: String,
    val returnType: String,
    // A17-N2: suspend overloads carry a discoverability remark at emission.
    val isSuspend: Boolean = false
)

/**
 * A17: a [KTypeParameter] view over a java.lang.reflect.TypeVariable, so a
 * static method's OWN generics can be DECLARED (`make<T>(param: Class<T>): T`)
 * instead of erased to their bounds (`make(param: Class<Object>): Object | null`).
 * [upperBounds] is lazy because an F-bounded variable (`T extends Comparable<T>`,
 * `T extends Enum<T>`) references itself through the substitution map that is
 * still being built when this is constructed.
 */
internal class JavaTypeVariableParameter(
    typeVariable: TypeVariable<*>,
    convert: (Type) -> KType
) : KTypeParameter {
    override val name: String = typeVariable.name
    override val isReified: Boolean = false
    override val variance: KVariance = KVariance.INVARIANT
    override val upperBounds: List<KType> by lazy {
        typeVariable.bounds.mapNotNull { bound ->
            try { convert(bound) } catch (_: Throwable) { null }
        }
    }
}

/**
 * TypeScript definition generator.
 *
 * Generates the content of a TypeScript definition file (.d.ts) that
 * covers a set of Kotlin and Java classes.
 *
 * This is useful when data classes are serialized to JSON and
 * handled in a JS or TypeScript web frontend.
 *
 * Supports:
 *  * Primitive types, with explicit int
 *  * Kotlin and Java classes
 *  * Data classes
 *  * Enums
 *  * Any type
 *  * Generic classes, without type erasure
 *  * Generic constraints
 *  * Class inheritance
 *  * Abstract classes
 *  * Lists as JS arrays
 *  * Maps as JS objects
 *  * Null safety, even inside composite types
 *  * Java beans
 *  * Mapping types
 *  * Customizing class definitions via transformers
 *  * Parenthesis are placed only when they are needed to disambiguate
 *
 * @constructor
 *
 * @param rootClasses Initial classes to traverse. Enough definitions
 * will be created to cover them and the types of their properties
 * (and so on recursively).
 *
 * @param mappings Allows to map some JVM types with JS/TS types. This
 * can be used e.g. to map LocalDateTime to JS Date.
 *
 * @param classTransformers Special transformers for certain subclasses.
 * They allow to filter out some classes, customize what methods are
 * exported, how they names are generated and what types are generated.
 *
 * @param ignoreSuperclasses Classes and interfaces specified here will
 * not be emitted when they are used as superclasses or implemented
 * interfaces of a class.
 *
 * @param intTypeName Defines the name integer numbers will be emitted as.
 * By default it's number, but can be changed to int if the TypeScript
 * version used supports it or the user wants to be extra explicit.
 */
class TypeScriptGenerator(
    rootClasses: Iterable<KClass<*>>,
    mappings: Map<KClass<*>, String> = mapOf(),
    classTransformers: List<ClassTransformer> = listOf(),
    ignoreSuperclasses: Set<KClass<*>> = setOf(),
    private val intTypeName: String = "number",
    private val voidType: VoidType = VoidType.NULL,
    private val kdocSource: KDocSource? = null
) {


    // Either a freshly-reflected [TypeScriptModule] or a [CachedModule] reused
    // verbatim from a prior run (see ModuleCache). The output writer and the
    // cross-module import-path lookups only need path + moduleText.
    interface GeneratedModule {
        val path: String
        val moduleText: String
        val definition: String
    }

    /** A module reused verbatim from the on-disk cache -- no reflection. */
    class CachedModule(
        override val path: String,
        override val moduleText: String,
    ) : GeneratedModule {
        override val definition: String get() = moduleText
    }

    // We make an assumption of the Modules WILL contain only 1 class
    // because there is no reliable way of dumping information at runtime
    // source: claude-3-5-sonnet
    inner class TypeScriptModule(
        val klass: KClass<*>,
        // A14: emit ONLY the static surface (no heritage/ctors/instance
        // members). Used for collection-shaped classes whose INSTANCES render
        // structurally (arrays/maps) and which therefore had no module at all,
        // leaving their static factories (ImmutableList.of, List.of, ...)
        // unreachable for Java.type()/registry consumers.
        private val staticsOnly: Boolean = false
    ) : GeneratedModule {
        override val path: String

        val dependentTypes = mutableSetOf<KClass<*>>()

        // W12b: when two dependent types share a simple name (e.g. LB's
        // config/types/Value and org.graalvm.polyglot.Value), importing both as
        // `Value` is a TS2300 duplicate identifier. This maps the losing
        // type(s) to a disambiguated alias used at both the import and every
        // reference site (see tsNameFor / the two-pass in init).
        val typeAliases = mutableMapOf<KClass<*>, String>()

        // Functional interfaces whose SAM is currently being rendered as an
        // arrow. A SAM that references its own interface type (directly or
        // through another functional interface) would otherwise recurse
        // forever; on re-entry we emit the nominal name instead.
        // NOTE: must be declared BEFORE the init block below — generateDefinition()
        // runs from init, and Kotlin initializes properties in declaration order.
        private val functionalInterfacesBeingRendered = mutableSetOf<Class<*>>()

        // A13: set by mapFromKType when any Map reference rendered as
        // `JavaMap<...>`; moduleText then imports the synthetic root-level
        // JavaMap module. Idempotent across the W12b second render pass.
        // NOTE: like functionalInterfacesBeingRendered, must be declared
        // BEFORE the init block (generateDefinition() runs from init and
        // Kotlin initializes properties in declaration order — declared
        // later, the `= false` initializer would clobber the flag).
        private var usesJavaMap = false

        /** The TS name to reference [kClass] by in this module — its alias if
         * it collided with another import (or this class's own name), else its
         * simple binary name. */
        private fun tsNameFor(kClass: KClass<*>): String =
            typeAliases[kClass] ?: kClass.binaryName()

        override var definition: String

        override val moduleText: String by lazy {
            val depth = path.count { it == '/' }

            // A13: a module that rendered any Map reference imports the
            // synthetic JavaMap module from the types/ root (same relative
            // depth computation as the dependent-type imports below). The
            // flag may over-fire on a speculative formatKType evaluation
            // whose rendered string was later deduped/discarded — an unused
            // import is harmless in a .d.ts and Part C of the typecheck gate
            // only requires the specifier to resolve.
            val javaMapImport = if (usesJavaMap)
                "import type { JavaMap } from '${"../".repeat(depth)}$JAVA_MAP_MODULE_PATH'\n"
            else
                ""

            javaMapImport + dependentTypes.sortedBy { it.qualifiedName ?: it.simpleName ?: "" }.map {
                val name = it.binaryName()
                val alias = typeAliases[it]
                // A dependent type may have no emitted module (it was skipped, e.g. a
                // class whose reflection failed under Kotlin 2.4.0). Computed lazily
                // after every module is built, so the check is order-independent.
                // A17-N4: the old behavior silently dropped the IMPORT while the
                // already-rendered NAME stayed in the definition body — a dangling
                // identifier (TS2304, masked only by consumers' skipLibCheck; the
                // shipped BoundMethodHandle$Specializer ctor param). Emit a local
                // `type X = any` alias instead: legal TS, and the name resolves
                // honestly as an unknown type.
                val mod = modulesByName[moduleKeyOf(it)]
                    ?: return@map "type ${alias ?: name} = any; // A17-N4: dependency has no emitted module (skipped); aliased so the name resolves"
                val upLevels = "../".repeat(depth)
                val downPath = mod.path.removePrefix("/")

                val imported = if (alias != null) "$name as $alias" else name
                "import type { $imported } from '$upLevels$downPath'"
            }.joinToString("\n", postfix = "\n") { it } + "export " + definition
        }


        init {
            path = getFilePathForClassWithoutExtension(klass)

            definition = generateDefinition()

            // W12b: resolve same-simple-name collisions among the dependent
            // types (and against this class's own name) by aliasing the losing
            // ones, then regenerate so both imports and references use the
            // disambiguated names. Only collision files pay the second pass.
            // A13: "JavaMap" is seeded unconditionally (the flag isn't final
            // until after the first render pass) so a dependent class with
            // that simple name would be aliased to JavaMap_2 instead of
            // colliding with the synthetic import. No such class exists today
            // (only JavaMapPaletteUtil, a different name).
            val used = mutableSetOf(klass.binaryName(), "JavaMap")
            dependentTypes.sortedBy { it.qualifiedName ?: it.binaryName() }.forEach { dep ->
                val name = dep.binaryName()
                if (name in used) {
                    var n = 2
                    var alias = "${name}_$n"
                    while (alias in used) { n++; alias = "${name}_$n" }
                    typeAliases[dep] = alias
                    used += alias
                } else {
                    used += name
                }
            }
            if (typeAliases.isNotEmpty()) {
                dependentTypes.clear()
                definition = generateDefinition()
            }
        }

        private fun getFilePathForClassWithoutExtension(klass: KClass<*>): String {
            val packagePath = klass.java.`package`?.name?.replace('.', '/') ?: ""
            val className = klass.binaryName()
            return if (packagePath.isEmpty()) {
                "$className.d.ts"
            } else {
                "$packagePath/$className.d.ts"
            }
        }

        private fun generateDefinition(): String {
            if (staticsOnly) return generateStaticsOnlyClass(klass)
            return generateInterface(klass)
        }

        /**
         * A14: statics-only declaration for a collection-shaped class.
         *
         * References to such a type keep their structural rendering (`E[]`,
         * `Map<K, V>`, index signatures) and never import this module, so no
         * heritage, constructors or instance members are declared - only the
         * statics a `Java.type("...")` / registry consumer reaches. Always a
         * `class` (TS interfaces cannot hold statics, and only `export class`
         * files receive Java.type registry entries).
         *
         * The class's own type parameters ARE declared: static generic
         * signatures can reference them, and (pre-A13) a static could
         * reference the type nominally through mapFromKType (`Map.of(...):
         * Map<K, V>`), resolving to THIS declaration by shadowing the
         * JS-global Map — without the parameters that self-reference was
         * TS2315 ("not generic"). A13: mapFromKType now emits the imported
         * `JavaMap<K, V>` instead, so the shadow self-reference no longer
         * occurs for maps; the declared parameters are kept (still used by
         * the static generic signatures, and MORE truthful for `Map.of(...)`:
         * the returned instance has the instance surface this statics-only
         * class deliberately lacks).
         * Empty result (no statics) -> visitClass skips the module entirely.
         */
        private fun generateStaticsOnlyClass(klass: KClass<*>): String {
            val statics = staticFieldsOf(klass) + staticMethodsOf(klass)
            if (statics.isBlank()) return ""
            val templateParameters = formatTypeParameters(klass.typeParameters)
            return "class ${klass.binaryName()}$templateParameters {\n" +
                    "    // A14 statics-only surface: instances of this collection-backed type\n" +
                    "    // render structurally (arrays / maps); only the statics are declared here.\n" +
                    statics +
                    "}"
        }


        private fun formatKType(
            kType: KType,
            isInTypeConstraint: Boolean = false,
            transformFunctionalInterface: Boolean = true
        ): TypeScriptType {
            val classifier = kType.classifier

            // Kotlin function types carry their parameter/return types in
            // kType.arguments — emit a real TS arrow `(param0: A, param1: B) => R`
            // instead of the nominal `FunctionN<...>` (or `UNKNOWN` for the
            // null-classifier suspend function types). This recovers type
            // information reflection already has; without it `(T) -> Unit`
            // surfaced as `Function1<T, void>` and `suspend (T) -> Unit` as
            // `UNKNOWN`. Done before the KClass branch so the function classifier
            // is not added to dependentTypes (no spurious FunctionN import).
            val functionArrow = kotlinFunctionArrow(kType)

            if (functionArrow == null && classifier is KClass<*>) {
                val existingMapping = predefinedMappings[classifier]
                    ?: predefinedMappingsByName[classifier.qualifiedName]
                if (existingMapping != null) {
                    // When in a type constraint, we shouldn't add the nullable union type
                    return TypeScriptType.single(
                        existingMapping,
                        kType.isMarkedNullable && !isInTypeConstraint,
                        voidType
                    )
                }
                // Skip anonymous/local classes ($1): they have no emitted module, so
                // importing them resolves (via the shared null-name bucket) to an
                // unrelated $1 class -> TS2305. nonPrimitiveFromKType renders them as
                // their supertype/`any`, so no import is needed.
                // A17-N4: also skip ACC_SYNTHETIC classes - javac's constructor
                // access-tag classes (BestCandidateSampling$1) are synthetic but
                // NOT anonymous/local (null EnclosingMethod method ref), so they
                // slipped this guard and got imported/emitted as bogus modules.
                val isAnon = try {
                    classifier.java.isAnonymousClass || classifier.java.isLocalClass || classifier.java.isSynthetic
                } catch (_: Throwable) { false }
                if (!shouldIgnoreSuperclass(classifier) && !isSameClass(classifier, klass) && !isAnon
                    && !predefinedMappings.containsKey(classifier)
                    && (classifier.qualifiedName == null
                        || !predefinedMappingsByName.containsKey(classifier.qualifiedName)))
                    dependentTypes.add(classifier)
            }

            val nullable = kType.isMarkedNullable && !isInTypeConstraint

            // A nullable function type must parenthesize the arrow before the
            // `| null` union: `((x: A) => B) | null`, NOT `(x: A) => B | null`
            // (which TS reads as a non-null function returning `B | null`).
            val arrowType = functionArrow?.let { if (nullable) "($it)" else it }

            val classifierTsType =
                arrowType
                ?: if (classifier is KClass<*>) {

                    val javaClass = classifier.java

                    if (isFunctionalInterface(javaClass) && transformFunctionalInterface) {
                        // Same nullable-arrow precedence guard as kotlinFunctionArrow:
                        // a nullable Java functional interface (`Runnable?`, `Consumer<T>?`)
                        // must be `((…) => R) | null`, not `(…) => R | null`.
                        val arrow = formatFunctionalInterfaceType(javaClass, kType)
                        if (nullable) "($arrow)" else arrow
                    } else {
                        predefinedMappings.getOrDefault(
                            classifier,
                            if (try { classifier.objectInstance != null } catch (_: Throwable) { false })
                                // Kotlin object singleton that also implements Iterable/Collection:
                                // emit as its named type, not as (Object | null)[].
                                // Use binaryName() directly — nonPrimitiveFromKType recurses
                                // through type arguments and can stack-overflow on unusual
                                // parameterised singleton types encountered in the wild.
                                // Guard with try-catch: JVM-module-private objects (e.g. coroutines
                                // internal markers) throw IllegalAccessException on objectInstance.
                                tsNameFor(classifier)
                            else if (Iterable::class.java.isAssignableFrom(classifier.java)
                                || classifier.javaObjectType.isArray
                            )
                                arrayFromKType(kType)
                            else if (Map::class.java.isAssignableFrom(classifier.java))
                                try {
                                    mapFromKType(kType)
                                } catch (_: Exception) {
                                    nonPrimitiveFromKType(kType)
                                }
                            else
                                nonPrimitiveFromKType(kType)
                        )
                    }
                } else if (classifier is KTypeParameter)
                    classifier.name
                else
                    "UNKNOWN" // giving up

            // When in a type constraint, we shouldn't add the nullable union type
            return TypeScriptType.single(classifierTsType, nullable, voidType)
        }

        /**
         * If [kType] is a Kotlin function type, render it as a TypeScript arrow
         * `(param0: A, param1: B) => R`; otherwise return null.
         *
         * Two shapes reach us:
         *  - Ordinary function types (`(A) -> B`, `Function2<A, B, R>`): the
         *    classifier is the arity-specific `kotlin.FunctionN` class and the
         *    parameter/return types are in `kType.arguments` (last = return).
         *  - Suspend function types (`suspend (A) -> R`): kotlin-reflect reports
         *    a `null` classifier, but the arguments are still present. We detect
         *    these via the arguments plus a cheap `toString()` shape check (used
         *    only for detection — the types themselves still come from the
         *    structured `arguments`, never from string parsing).
         */
        private fun kotlinFunctionArrow(kType: KType): String? {
            val classifier = kType.classifier
            val isFunctionType = when (classifier) {
                is KClass<*> ->
                    classifier.qualifiedName?.let { FUNCTION_CLASS_NAME.matches(it) } == true
                null -> kType.arguments.isNotEmpty() && kType.toString().let {
                    it.startsWith("(") || it.startsWith("suspend (")
                }
                else -> false
            }
            if (!isFunctionType) return null

            val args = kType.arguments
            if (args.isEmpty()) return null

            val params = args.dropLast(1).mapIndexed { index, arg ->
                "param$index: ${formatKType(arg.type ?: KotlinAnyOrNull).formatWithoutParenthesis()}"
            }.joinToString(", ")
            val returnType = args.last().type
                ?.let { formatKType(it).formatWithoutParenthesis() }
                ?: "any"
            return "($params) => $returnType"
        }


        private fun nonPrimitiveFromKType(kType: KType): String {
            val kClass = kType.classifier as KClass<*>

            // Anonymous/local classes (Foo$1) have no qualifiedName and no emitted
            // module. Rendering their binary name emits an import for a member that
            // doesn't exist — and because every null-name class shares one lookup
            // bucket, the import even resolves to an UNRELATED Foo$1 (TS2305). Fall
            // back to the first real supertype (the meaningful type the anonymous
            // class stands in for), else `any`.
            // A17-N4: (a) ACC_SYNTHETIC classes (javac ctor access tags, lambda
            // hidden classes) match the guard too — they are neither anonymous nor
            // local, so they used to fall through and emit a raw binary name;
            // (b) detection and fallback are now SEPARATE try-scopes: a class that
            // matched the guard but whose kotlin-reflect `.supertypes` throws must
            // render `any`, NEVER the raw binary name (the old single try swallowed
            // the throw and fell through to exactly that — the shipped dangling
            // `ExecutionSequencer$1` ctor params, TS2304 under skipLibCheck:false).
            val isAnonOrSynthetic = try {
                kClass.java.isAnonymousClass || kClass.java.isLocalClass || kClass.java.isSynthetic
            } catch (_: Throwable) { false }
            if (isAnonOrSynthetic) {
                return try {
                    val sup = kClass.supertypes.firstOrNull {
                        val c = it.classifier as? KClass<*>
                        c != null && !isSameClass(c, Any::class) && c.qualifiedName != "java.lang.Object"
                    }
                    if (sup != null) formatKType(sup).formatWithoutParenthesis() else "any"
                } catch (_: Throwable) {
                    "any"
                }
            }

            val binaryName = tsNameFor(kClass)

            // If the counts don't match, this might indicate a specialized type
            // This is actually infuriating and fucking frustrating that Kotlin does not fucking
            // provided a well-defined way of getting the actual type of specialized class

            // Example: your kType evaluates to
            // kotlin.reflect.KFunction1<me.ntrrgc.tsGenerator.tests.ClassWithMethodsThatReturnsOrTakesFunctionalType, () -> () -> kotlin.Int>
            // in debugger and kType.classifier evaluates to class kotlin.reflect.KFunction (not the KFunction1)
            // but you can see there is definitely no way of acquiring the actual type with proper API
            // would you rather rely on .toString() and parse it and rely on the alternative shitty hack?
            // place the breakpoint and see for yourself
            // A type parameter constrained to something other than Object/Any. Both
            // padding a missing argument AND erasing a method type-variable to Object
            // against such a parameter violate its bound (TS2344, the dominant bucket:
            // `EnumCodec<Object>` where E : Enum<E> & StringRepresentable, `<T : Entity>`,
            // ...). `any` satisfies any constraint; `Object` is kept for unconstrained
            // parameters (so `Class<Object>` stays precise).
            fun constrained(tp: KTypeParameter): Boolean = try {
                tp.upperBounds.any { b ->
                    val c = b.classifier
                    !(c is KClass<*> && (isSameClass(c, Any::class) || c.qualifiedName == "java.lang.Object"))
                }
            } catch (_: Throwable) { false }
            fun isErasedObject(t: KType?): Boolean {
                val c = (t?.classifier as? KClass<*>) ?: return t == null
                return isSameClass(c, Any::class) || c.qualifiedName == "java.lang.Object"
            }

            if (kType.arguments.size != kClass.typeParameters.size) {
                return binaryName + if (kClass.typeParameters.isNotEmpty()) "<${
                    kClass.typeParameters.joinToString(", ") { if (constrained(it)) "any" else "Object" }
                }>" else ""
            }

            // Counts match: emit the arguments, but an Object that erased against a
            // constrained parameter is an artifact (Object can't legally hold there) —
            // emit `any` rather than a bound-violating `Object`.
            return binaryName + if (kType.arguments.isNotEmpty()) {
                "<" + kType.arguments.mapIndexed { i, arg ->
                    val tp = kClass.typeParameters.getOrNull(i)
                    if (tp != null && constrained(tp) && isErasedObject(arg.type)) "any"
                    else formatKType(arg.type ?: KotlinNotNull, true).formatWithoutParenthesis()
                }.joinToString(", ") + ">"
            } else ""
        }


        private fun getIterableElementType(kType: KType): KType? {
            // Traverse supertypes to find `Iterable<T>`
            val classifier = kType.classifier as? KClass<*> ?: return null
            val iterableSupertype = try {
                classifier.supertypes
                    .firstOrNull { it.classifier == Iterable::class } ?: return null
            } catch (throwable: Throwable) {
                return null
            }

            // Extract the type argument of `Iterable<T>`. It is expressed in
            // terms of the CLASSIFIER's own type parameters (`Collection<out E>
            // : Iterable<E>` yields `E`), not the actual arguments of [kType] —
            // substitute them, else `E` leaks into the emitted .d.ts as an
            // undeclared identifier (e.g. `collection: E[]` for a
            // `Collection<T>` parameter). Arguments that aren't resolvable
            // (star projections, raw types) fall back to the variable's bound.
            val element = iterableSupertype.arguments.firstOrNull()?.type ?: return null
            val substitution = classifier.typeParameters.mapIndexedNotNull { index, parameter ->
                kType.arguments.getOrNull(index)?.type?.let { parameter.name to it }
            }.toMap()
            return substituteTypeParameters(element, substitution, fallbackToBound = true)
        }

        /**
         * Replaces [KTypeParameter] references in [kType] (recursively, through
         * generic arguments) with the actual types given in [substitution]
         * (keyed by parameter name).
         *
         * With [fallbackToBound], parameters missing from the map are replaced
         * by their first upper bound (or `Any?`) instead of being kept — used
         * where the surrounding declaration cannot legally reference them.
         */
        private fun substituteTypeParameters(
            kType: KType,
            substitution: Map<String, KType>,
            fallbackToBound: Boolean = false,
            seen: Set<String> = emptySet()
        ): KType {
            val classifier = kType.classifier
            return when {
                classifier is KTypeParameter -> {
                    val replacement = when {
                        classifier.name in substitution -> substitution.getValue(classifier.name)
                        fallbackToBound && classifier.name !in seen ->
                            classifier.upperBounds.firstOrNull()?.let {
                                // `seen` guards recursive bounds (T : Comparable<T>)
                                substituteTypeParameters(it, substitution, true, seen + classifier.name)
                            } ?: KotlinAnyOrNull

                        else -> return kType
                    }
                    if (kType.isMarkedNullable && !replacement.isMarkedNullable)
                        replacement.withNullability(true)
                    else
                        replacement
                }

                classifier is KClass<*> && kType.arguments.isNotEmpty() -> {
                    val newArguments = kType.arguments.map { projection ->
                        val type = projection.type ?: return@map projection
                        val substituted = substituteTypeParameters(type, substitution, fallbackToBound, seen)
                        if (substituted == type) projection else KTypeProjection(projection.variance, substituted)
                    }
                    if (newArguments == kType.arguments) kType
                    else try {
                        classifier.createType(newArguments, kType.isMarkedNullable)
                    } catch (_: Throwable) {
                        // arity mismatch on specialized types — keep the original
                        kType
                    }
                }

                else -> kType
            }
        }

        /** Recursively collects every [KTypeParameter] referenced by [kType]
         * (including ones nested in generic arguments) that is not declared by
         * the class ([declared]) and not collected yet. These must be declared
         * on the member, or the emitted identifier is undefined. */
        private fun collectFreeTypeParameters(
            kType: KType,
            declared: List<KTypeParameter>,
            into: MutableList<KTypeParameter>
        ) {
            val classifier = kType.classifier
            if (classifier is KTypeParameter
                && declared.none { it.name == classifier.name }
                && into.none { it.name == classifier.name }
            ) {
                into.add(classifier)
            }
            kType.arguments.forEach { argument ->
                argument.type?.let { collectFreeTypeParameters(it, declared, into) }
            }
        }

        private fun arrayFromKType(kType: KType): String {
            // Use native JS array
            // Parenthesis are needed to disambiguate complex cases,
            // e.g. (Pair<string|null, int>|null)[]|null
            val itemType = when (kType.classifier) {
                // Native Java arrays... unfortunately simple array types like these
                // are not mapped automatically into kotlin.Array<T> by kotlin-reflect :(
                IntArray::class -> Int::class.createType(nullable = false)
                ShortArray::class -> Short::class.createType(nullable = false)
                ByteArray::class -> Byte::class.createType(nullable = false)
                CharArray::class -> Char::class.createType(nullable = false)
                LongArray::class -> Long::class.createType(nullable = false)
                FloatArray::class -> Float::class.createType(nullable = false)
                DoubleArray::class -> Double::class.createType(nullable = false)
                BooleanArray::class -> Boolean::class.createType(nullable = false)

                // Class container types (they use generics)
                else -> {
                    getIterableElementType(kType) ?: kType.arguments.singleOrNull()?.type ?: KotlinAnyOrNull
                }
            }
            // Self-iterable (Path : Iterable<Path>, iterating yields subpaths):
            // a REFERENCE to such a type is one value, not a collection - emit
            // the nominal type. Appending [] here arrayed every Path in the
            // output (toPath(): Path[], getParent(): Path[], even
            // Comparable<Path[]>). Gate on the same isSelfIterable predicate
            // the declaration side uses (it keeps these classes a module, so
            // the nominal name imports) - NOT on classifier equality with the
            // element type, which misfires on shapes like List<String[]> and
            // emits an unimported `List<string[]>` (TS2304).
            val classifier = kType.classifier
            return if (classifier is KClass<*> && isSelfIterable(classifier))
                nonPrimitiveFromKType(kType)
            else
                "${formatKType(itemType).formatWithParenthesis()}[]"
        }

        // https://github.com/ntrrgc/ts-generator/pull/39/files#diff-15868d315697c109f701fa6b29d6b1beaabb6c461122d4cbca76194bba08da6eR194
        // GPLv3 does not apply for this function
        private fun mapFromKType(kType: KType): String {
            // A13: every Java/Kotlin Map reference renders as the synthetic
            // `JavaMap<K, V>` interface (types/JavaMap.d.ts) — the GraalJS
            // host surface of a java.util.Map, mirroring the runtime-verified
            // F8 localStorage facade. The three previous forms all lied at
            // runtime: index signatures (`{ [key: string]: V }`) invited
            // bracket access that a host Map does not honor, and the
            // JS-global `Map<K, V>` named a real lib type whose entire API
            // (`.set`, `.size` as a property) is wrong on a host object.
            // Uniform in return AND parameter position; position-aware
            // widening for input positions is a documented follow-up.
            usesJavaMap = true

            // Maps whose reflected type-argument count isn't 2 (fastutil primitive
            // maps like Int2ObjectMap<V> carry one type param because the key is a
            // primitive; raw Map usage and Map subtypes fixing K/V also land here)
            // would index past `arguments` and throw, dropping the call to the
            // nominal fallback -> an undefined `Int2ObjectMap` name (TS2304).
            // The true K/V is not recoverable from the class's own erased
            // arguments — `any, any` is honest; the supertype-walk projection
            // that could recover it is a documented follow-up backlog item.
            if (kType.arguments.size < 2) return "JavaMap<any, any>"

            val keyType = formatKType(kType.arguments[0].type ?: KotlinAnyOrNull)
            val valueType = formatKType(kType.arguments[1].type ?: KotlinAnyOrNull)

            return "JavaMap<${keyType.formatWithoutParenthesis()}, ${valueType.formatWithoutParenthesis()}>"
        }


        private fun generateInterface(klass: KClass<*>): String {
            val typeKeyword = when {
                klass.java.isInterface -> "interface"
                klass.isAbstract -> "abstract class"
                else -> "class"
            }

            val supertypes = try {
                klass.supertypes
                    .filterNot { it.classifier in ignoredSuperclasses }
                    // For singletons that implement Iterable/Collection/Map, strip those
                    // supertypes so the extends clause doesn't emit "(Object | null)[]".
                    // Safe for non-singletons: any non-singleton that is itself Iterable
                    // would have shouldIgnoreSuperclass=true and never reach generateInterface.
                    .filterNot { t ->
                        (t.classifier as? KClass<*>)?.let(shouldIgnoreSuperclass) == true
                    }
            } catch (throwable: Throwable) {
                emptyList<KType>()
            }

            val (interfaceSupertypes, classSupertypes) = supertypes.partition {
                val classifier = it.classifier
                classifier is KClass<*> && classifier.java.isInterface
            }

            val extendsString = if (supertypes.isNotEmpty()) {
                if (klass.java.isInterface) {
                    " extends " + supertypes.sortedWith(compareBy(
                        { (it.classifier as? KClass<*>)?.qualifiedName ?: it.toString() },
                        { it.toString() }
                    )).joinToString(", ") {
                        formatKType(it, false, false).formatWithoutParenthesis()
                    }
                } else {
                    val extendsClause = classSupertypes.take(1).map {
                        "extends ${formatKType(it, false, false).formatWithoutParenthesis()}"
                    }.firstOrNull() ?: ""

                    val implementsClause = if (interfaceSupertypes.isNotEmpty()) {
                        "implements " + interfaceSupertypes.sortedWith(compareBy(
                            { (it.classifier as? KClass<*>)?.qualifiedName ?: it.toString() },
                            { it.toString() }
                        )).joinToString(", ") {
                            formatKType(it, false, false).formatWithoutParenthesis()
                        } + " "
                    } else ""

                    " $extendsClause $implementsClause"
                }
            } else ""


            val templateParameters = formatTypeParameters(klass.typeParameters)



            val classDoc = try {
                kdocSource?.tsdocForFqn(klass.qualifiedName ?: "", "")
            } catch (_: Throwable) { null } ?: ""

            // W-#9: for Kotlin enums, emit an override of `name()` that
            // returns a string-literal union of the enum constants. This
            // lets script authors discriminate enum values via
            //   if (e.name() === "LINEAR") { ... }
            // and have TS narrow the literal correctly — without lying
            // about the runtime shape (Kotlin enums are Java enum objects
            // at runtime, not strings, so we keep the `class X extends
            // Enum<X>` form intact). See audit W9 + W19.
            val enumNameOverride = if (klass.java.isEnum) {
                try {
                    val constants = klass.java.enumConstants
                        ?.mapNotNull { (it as? Enum<*>)?.name }
                        ?.takeIf { it.isNotEmpty() }
                    constants?.let { names ->
                        val union = names.joinToString(" | ") { "\"$it\"" }
                        "    name(): $union;\n"
                    } ?: ""
                } catch (_: Throwable) { "" }
            } else ""

            return classDoc + "$typeKeyword ${klass.binaryName()}$templateParameters$extendsString{\n" +
                    // A17-N3: statics come from klass.java.methods ONLY - the old
                    // interfaceSupertypes merge copied interface statics onto every
                    // implementer, but Java interface static METHODS are not
                    // inherited (JLS 8.4.8): Java.type('...MutableComponent')
                    // .literal(...) fails at runtime despite the typing. Interface
                    // CONSTANTS (fields) genuinely are inherited and keep flowing
                    // through staticFieldsOf (klass.java.fields includes them).
                    (if (klass.java.isInterface) "" else
                        staticFieldsOf(klass) + staticMethodsOf(klass)) +
                    // A5: never emit constructors inside a TS interface - kotlin-
                    // reflect reports one for annotation classes, which produced
                    // `constructor()` in 629 interface files (TS7010/TS1093 debt).
                    (if (klass.java.isInterface) "" else constructorsOf(klass)) +
                    propertiesOf(klass, interfaceSupertypes) +
                    // A15 (Java bean-property dual) DISABLED. The per-class guards
                    // in nashornBeanPropertiesOf cannot see the cross-hierarchy
                    // damage: injecting e.g. `readonly attackable: boolean` onto
                    // Entity (from isAttackable()) makes Entity's structural shape
                    // gain a member its subtree renders incompatibly, and Entity is
                    // used as a generic bound (`<T extends Entity>`) all over MC -
                    // a full regen produced +84 TS2344 + TS2416 cascades. A correct
                    // dual needs whole-hierarchy analysis (only leaf classes, never
                    // a generic-bound type); until then the getter-method form is
                    // the sole surface (documented property-first in the README).
                    // nashornBeanPropertiesOf(klass) +
                    functionsOf(klass, interfaceSupertypes, klass.typeParameters) +
                    enumNameOverride +
                    "}"
        }

        private fun formatTypeParameters(typeParameters: List<KTypeParameter>): String {
            if (typeParameters.isEmpty()) {
                return ""
            }

            return "<" + typeParameters.distinctBy { it.name }.joinToString(", ") { typeParameter ->
                val bounds = typeParameter.upperBounds
                typeParameter.name + if (bounds.isNotEmpty()) {
                    " extends " + bounds.joinToString(" & ") { bound ->
                        // Pass true for isInTypeConstraint
                        if (bound.classifier is KClass<*> && isSameClass(
                                bound.classifier as KClass<*>,
                                Any::class
                            )
                        ) {
                            formatKType(bound) // unused result but needs to record dependencies
                            // A Java `<T>` (upper bound Any/Object) accepts ANY value,
                            // including arrays, void, maps and functions. The old
                            // `Object | number | string | boolean` union excluded all of
                            // those, so every instantiation with such an argument failed
                            // the bound (TS2344). `unknown` is the top type and accepts
                            // them all; applied uniformly it stays consistent across
                            // extends/implements chains.
                            "unknown"
                        } else
                            formatKType(bound, true, false).formatWithoutParenthesis()
                    }
                } else {
                    ""
                }
            } + ">"
        }


        private fun createKotlinType(javaClass: Class<*>): KType {
            // Reference arrays: String[].class.kotlin is Array::class (one type
            // parameter), which the padding branch below would fill with Any? -
            // `(Object | null)[]` instead of `string[]`. Recurse into the
            // component type instead (handles String[][] too). Primitive arrays
            // (int[].class -> IntArray) have no type parameters and take the
            // normal branch; arrayFromKType maps them via its primitive table.
            if (javaClass.isArray && !javaClass.componentType.isPrimitive) {
                return Array::class.createType(
                    listOf(KTypeProjection.invariant(createKotlinType(javaClass.componentType)))
                )
            }
            val kClass = javaClass.kotlin
            return if (kClass.typeParameters.isEmpty()) {
                kClass.createType()
            } else {
                // If the class has type parameters, create type with Any? for each parameter
                val typeArgs = kClass.typeParameters.map {
                    KTypeProjection.invariant(Any::class.createType(nullable = true))
                }
                kClass.createType(typeArgs)
            }
        }


        private fun staticFieldsOf(klass: KClass<*>): String = try {
            klass.java.fields
                .filter { Modifier.isStatic(it.modifiers) }
                .filter { !SYNTHETIC_MEMBER_REGEX.matches(it.name) }
                // A4: getFields() returns inherited public constants alongside a
                // shadowing redeclaration - both emitted = duplicate `static X`
                // (the TS2300 debt, e.g. BlockPos.CODEC next to Vec3i.CODEC).
                // One declaration per name; the class's own (most-specific) wins.
                .groupBy { it.name }
                .map { (_, fields) ->
                    // Deterministic pick (getFields order is JVM-dependent):
                    // the class's own declaration, else the lexicographically
                    // first generic signature.
                    fields.firstOrNull { it.declaringClass == klass.java }
                        ?: fields.minByOrNull { it.toGenericString() }!!
                }
                .sortedWith(compareBy({ it.name }, { it.toGenericString() }))
                .joinToString("") { field ->
                    val fieldName = field.name
                    val fieldType = javaTypeToKotlinType(field.genericType)

                    val visibility = when {
                        Modifier.isPrivate(field.modifiers) -> "// private "
                        Modifier.isProtected(field.modifiers) -> "protected "
                        else -> ""
                    }

                    val fieldDoc = try {
                        kdocSource?.tsdocForFqn("${klass.qualifiedName}.${fieldName}", "    ")
                    } catch (_: Throwable) { null } ?: ""
                    (fieldDoc + "    static $visibility$fieldName: ${formatKType(fieldType).formatWithoutParenthesis()};\n")
                        .commentIfInvalid()
                }
        } catch (exception: kotlin.reflect.jvm.internal.KotlinReflectionInternalError) {
            print(exception.toString())
            ""
        } catch (exception: NoClassDefFoundError) {
            print("Missing dependency: ${exception.message}")
            "" // Return empty string when dependency is missing
        } catch (exception: IllegalStateException) {
            print("Likely unable to infer accessibility: ${exception.message}")
            ""
        } catch (exception: Exception) {
            println("Other exceptions happend ${exception.message}")
            ""
        }

        /**
         * Converts a java.lang.reflect.Type to a KType so that it can be
         * rendered through [formatKType] (primitive mappings, dependentTypes
         * registration, aliasing) instead of leaking raw qualified names.
         *
         * [substitution] maps type-variable names to actual KTypes resolved at
         * the use site (e.g. `Predicate<String>` resolves Predicate's `T` to
         * String). Type variables that are NOT resolvable (true erasure) fall
         * back to their first bound's raw class, or Object — they must never
         * surface as undeclared identifiers in the emitted TypeScript.
         */
        private fun javaTypeToKotlinType(
            type: Type,
            substitution: Map<String, KType> = emptyMap()
        ): KType {
            return when (type) {
                is Class<*> -> createKotlinType(type)
                is ParameterizedType -> {
                    val rawType = (type.rawType as Class<*>).kotlin
                    val typeArgs = type.actualTypeArguments.map { arg ->
                        javaTypeToKotlinType(arg, substitution)
                    }
                    val projections = typeArgs.map { KTypeProjection.invariant(it) }
                    try {
                        rawType.createType(projections)
                    } catch (_: Throwable) {
                        // A17: an argument is a SyntheticKType (a declared JAVA
                        // method-level type variable) which kotlin-reflect's
                        // createType hard-casts and rejects; carry the shape in
                        // a SyntheticKType instead so `Class<T>` stays `Class<T>`.
                        SyntheticKType(rawType, projections)
                    }
                }

                is TypeVariable<*> -> substitution[type.name] ?: eraseToBound(type)
                is WildcardType ->
                    type.upperBounds.firstOrNull()?.let { javaTypeToKotlinType(it, substitution) }
                        ?: KotlinAnyOrNull

                // T[] / List<X>[] from generic signatures (incl. the array part
                // of varargs). Without this case they fell into the Any? catch-all,
                // erasing the ARRAYNESS itself to a scalar `Object | null` on the
                // whole reflection path (statics, SAMs, fields).
                is GenericArrayType -> {
                    val component = javaTypeToKotlinType(type.genericComponentType, substitution)
                    val projections = listOf(KTypeProjection.invariant(component))
                    try {
                        Array::class.createType(projections)
                    } catch (_: Throwable) {
                        // A17: same SyntheticKType fallback as ParameterizedType.
                        SyntheticKType(Array::class, projections)
                    }
                }

                else -> Any::class.createType(nullable = true)
            }
        }

        /** Fallback for a type variable we cannot resolve at the use site:
         * its first bound's raw class (nullable, like the old `Any?` fallback),
         * or `Any?` when there is no usable bound. Never the bare variable name.
         *
         * Only NON-GENERIC bounds are used: a generic bound is typically
         * F-bounded (`T extends Enum<T>`, `T extends Comparable<T>`), and
         * erasing it to the raw class gets its missing type arguments padded
         * with `Object` downstream — `Enum<Object>` — which violates the
         * bound's own constraint and emits TS2344 at every use site. Those
         * erase to `Any?` as before. */
        private fun eraseToBound(typeVariable: TypeVariable<*>): KType {
            val rawBound = when (val bound = typeVariable.bounds.firstOrNull()) {
                is Class<*> -> bound
                is ParameterizedType -> bound.rawType as? Class<*>
                else -> null
            } ?: return KotlinAnyOrNull
            if (rawBound.typeParameters.isNotEmpty()) return KotlinAnyOrNull
            return createKotlinType(rawBound).withNullability(true)
        }


        /**
         * A12: resolve the Kotlin declaration behind a JVM static method
         * (a @JvmStatic bridge on an object/companion, a file-facade top-level
         * function, or a top-level extension function), so the DECLARED Kotlin
         * types - crucially return/parameter nullability, which plain
         * java.lang.reflect erases - survive into the emitted statics.
         * Gated on the Kotlin Metadata annotation: pure-Java statics never
         * resolve, keeping the reflect path (and its output) untouched there.
         */
        private fun staticKotlinDual(method: Method): KFunction<*>? = try {
            if (method.declaringClass.getAnnotation(Metadata::class.java) != null)
                method.kotlinFunction
            else null
        } catch (_: Throwable) {
            null
        }

        // A17-N3: no interfaceSupertypes parameter - see the call-site note in
        // generateDefinition. klass.java.methods already follows the Java
        // inheritance rule (interface statics excluded), so it is the sole source.
        private fun staticMethodsOf(klass: KClass<*>): String = try {
            klass.java.methods.asSequence()
                .filter { Modifier.isStatic(it.modifiers) }
                .filter { !MIXIN_COUNTER_REGEX.containsMatchIn(it.name) }
                .filter { !SYNTHETIC_MEMBER_REGEX.matches(it.name) }
                .sortedWith(compareBy({ it.name }, { it.toGenericString() }))
                .map { method ->
                    val methodName = method.name

                    val visibility = when {
                        Modifier.isPrivate(method.modifiers) -> "// private "
                        Modifier.isProtected(method.modifiers) -> "protected "
                        else -> ""
                    }

                    val methodDoc = try {
                        kdocSource?.tsdocForFqn("${klass.qualifiedName}.${methodName}", "    ")
                    } catch (_: Throwable) { null } ?: ""

                    val kotlinDual = staticKotlinDual(method)
                    if (kotlinDual != null) {
                        // A12: render from the Kotlin function. Drop the INSTANCE
                        // parameter (the object/companion a @JvmStatic bridge binds
                        // internally - not a JVM parameter of the static), but keep
                        // an EXTENSION_RECEIVER (it IS the first JVM parameter).
                        // Real Kotlin parameter names come along for free.
                        val fnParams = kotlinDual.parameters
                            .filter { it.kind != KParameter.Kind.INSTANCE }
                        val parameters = fnParams.joinToString(", ") { param ->
                            val rendered = formatKType(param.type).formatWithoutParenthesis()
                            val rest = if (param.isVararg && param === fnParams.last() && rendered.endsWith("[]") && !rendered.contains("=>")) "..." else ""
                            "${rest}${param.name ?: "self"}: $rendered"
                        }
                        // A17: declare the function's own type variables (a JVM
                        // static never sees class-level ones) so they don't leak
                        // undeclared (TS2304).
                        val freeParams = mutableListOf<KTypeParameter>()
                        collectFreeTypeParameters(kotlinDual.returnType, emptyList(), freeParams)
                        fnParams.forEach { collectFreeTypeParameters(it.type, emptyList(), freeParams) }
                        val returnRendered = formatKType(kotlinDual.returnType).formatWithoutParenthesis()
                        (methodDoc + "    static $visibility$methodName${formatTypeParameters(freeParams)}($parameters): $returnRendered;\n")
                            .commentIfInvalid()
                    } else {
                        // A17: declare the method's OWN generics instead of erasing
                        // them to their bounds. Before, `static <T> T make(Class<T>)`
                        // emitted `make(param: Class<Object | null>): Object | null`
                        // (the dead `filterIsInstance<KTypeParameter>` over KTypes
                        // never declared anything); now it emits
                        // `make<T>(param: Class<T>): T`. F-bounded bounds
                        // (`T extends Enum<T>`) render legally because T is declared,
                        // avoiding the old constraint-violating `Enum<Object>` (TS2344).
                        val substitution = mutableMapOf<String, KType>()
                        method.typeParameters.forEach { typeVariable ->
                            substitution[typeVariable.name] = SyntheticKType(
                                JavaTypeVariableParameter(typeVariable) { javaTypeToKotlinType(it, substitution) }
                            )
                        }
                        val returnType = javaTypeToKotlinType(method.genericReturnType, substitution)

                        val parameters = method.parameters
                            .joinToString(", ") { param ->
                                val paramType = javaTypeToKotlinType(param.parameterizedType, substitution)

                                // A trailing Java vararg is callable with spread args (as
                                // GraalJS + the LB KDoc examples do), so emit a TS rest
                                // param `...name: T[]`. Only when it is genuinely the last
                                // param AND renders as an array type - Kotlin allows a
                                // non-last vararg (TS1014) and some varargs render as their
                                // element type (TS2370); those keep the plain array form.
                                val rendered = formatKType(paramType).formatWithoutParenthesis()
                                val rest = if (param.isVarArgs && param === method.parameters.last() && rendered.endsWith("[]") && !rendered.contains("=>")) "..." else ""
                                "${rest}param${param.name}: $rendered"
                            }

                        val typeParamsString = formatTypeParameters(
                            method.typeParameters.mapNotNull { substitution[it.name]?.classifier as? KTypeParameter }
                        )

                        (methodDoc + "    static $visibility$methodName$typeParamsString($parameters): ${formatKType(returnType).formatWithoutParenthesis()};\n")
                            .commentIfInvalid()
                    }
                }
                // A4: distinct Java overloads (int/long, float/double) can collapse
                // to the same rendered TS line - emit each line once. The input is
                // sorted, so the surviving occurrence is deterministic.
                .distinct()
                .joinToString("")
        } catch (exception: kotlin.reflect.jvm.internal.KotlinReflectionInternalError) {
            print(exception.toString())
            ""
        } catch (exception: NoClassDefFoundError) {
            print("Missing dependency: ${exception.message}")
            "" // Return empty string when dependency is missing
        } catch (exception: IllegalStateException) {
            print("Likely unable to infer accessibility: ${exception.message}")
            ""
        } catch (exception: Exception) {
            println("Other exceptions happend ${exception.message}")
            ""
        }


        private fun constructorsOf(klass: KClass<*>): String = try {
            // Wrap the entire reflection process in a try-catch
            try {
                // Force early class loading to trigger any NoClassDefFoundError
                klass.java.declaredConstructors

                // A17-N4: drop ACC_SYNTHETIC constructors. These are compiler
                // bookkeeping, uncallable as written: kotlinc's default-args
                // bridge `(..., int mask, DefaultConstructorMarker)` (shipped in
                // 170 files when kotlin-reflect falls back to Java reflection),
                // javac's private-ctor access bridges taking a `Foo$1` tag class
                // (the dangling-`$1`-import sites), and kotlinc's value-class
                // mangled ctors. @JvmOverloads reduced-arity ctors are NOT
                // synthetic and are unaffected (A11 plan, verified via javap).
                val ctorCandidates = klass.constructors
                val allCtors = ctorCandidates
                    .filterNot { c ->
                        try { c.javaConstructor?.isSynthetic == true } catch (_: Throwable) { false }
                    }
                    .sortedBy { it.toString() }
                if (allCtors.isEmpty() && ctorCandidates.isNotEmpty()) {
                    // A17-N4 sentinel: the class HAD constructors but every one was
                    // synthetic. Emitting nothing would imply a default PUBLIC
                    // `constructor()` in TS. PROTECTED (not private!) blocks external
                    // `new` while still allowing subclasses to extend — a `private
                    // constructor()` here throws TS2675 on every emitted subclass
                    // (e.g. LazyJavaScope / FunctionTypeKind ARE extended in-tree;
                    // scratch-verified with the repo's tsc).
                    "    protected constructor()\n"
                } else {
                // TypeScript forbids mixed visibility across overloads of the same
                // member (TS2385), but a Java class routinely has e.g. a public AND
                // a private constructor. Normalize:
                //   - if the class is publicly constructible, emit ONLY its public
                //     constructors (private/protected overloads aren't public API,
                //     and a consumer couldn't call them anyway);
                //   - otherwise emit ALL of them under ONE uniform modifier so a
                //     non-constructible class still can't be `new`-ed (W-#14).
                // Only PRIVATE/PROTECTED are "non-public". Everything else —
                // PUBLIC, INTERNAL, and crucially the NULL/unknown visibility that
                // kotlin-reflect reports for many *Java* constructors — is treated
                // as public (constructible), matching the prior `else -> ""`. Doing
                // otherwise marks those classes' ctors private and breaks every
                // subclass with TS2675 ("cannot extend; constructor is private").
                val isNonPublicCtor = { c: KFunction<*> ->
                    c.visibility == KVisibility.PRIVATE || c.visibility == KVisibility.PROTECTED
                }
                val hasPublic = allCtors.any { !isNonPublicCtor(it) }
                val emitCtors = if (hasPublic) allCtors.filterNot(isNonPublicCtor) else allCtors
                val uniformVisibility = when {
                    hasPublic -> ""
                    allCtors.any { it.visibility == KVisibility.PROTECTED } -> "protected "
                    else -> "private "
                }
                // Constructors can't declare type parameters in TS. Preserve the
                // class's own parameters (map each to itself) and let fallbackToBound
                // replace any OTHER variable a ctor signature references — a ctor-
                // level `M`, or an enclosing class's `T` inside a nested class — with
                // its upper bound, else it leaks as an undeclared name (TS2304).
                val classTypeParamSelfMap = klass.typeParameters.associate { it.name to it.createType() }
                emitCtors.joinToString("") { constructor ->
                    // A11 (constructor rider): a trailing run of Kotlin-defaulted
                    // params whose @JvmOverloads reduced-arity ctors exist in
                    // bytecode renders optional (`a?:`) — `new Color4b(r, g, b)`
                    // was a shipped type error despite the 3-arg ctor existing.
                    // Same double gate as the instance-method path (see helper).
                    val ctorParams = constructor.parameters
                    val droppable = jvmDroppableTrailingCtorParams(klass, constructor)
                    val firstOptional = ctorParams.size - droppable
                    val parameters = ctorParams.withIndex()
                        .joinToString(", ") { (index, param) ->
                            val rawType =
                                pipeline.transformFunctionParameterType(param.type, param, constructor, klass)
                            val paramType = substituteTypeParameters(
                                rawType, classTypeParamSelfMap, fallbackToBound = true
                            )
                            // Kotlin vararg -> TS rest param `...name: T[]`, but only
                            // when it is genuinely last and renders as an array type
                            // (a non-last vararg -> TS1014, a non-array one -> TS2370).
                            val rendered = formatKType(paramType).formatWithoutParenthesis()
                            val rest = if (param.isVararg && param === ctorParams.last() && rendered.endsWith("[]") && !rendered.contains("=>")) "..." else ""
                            // A rest param cannot also be optional (TS1047); moot in
                            // practice (Kotlin varargs can't have defaults) but cheap.
                            val optional = if (index >= firstOptional && rest.isEmpty()) "?" else ""
                            "${rest}${param.name}${optional}: $rendered"
                        }
                    val ctorDoc = try {
                        kdocSource?.tsdocForFqn("${klass.qualifiedName}.<init>", "    ")
                    } catch (_: Throwable) { null } ?: ""
                    (ctorDoc + "    ${uniformVisibility}constructor($parameters)\n")
                        .commentIfInvalid()
                }
                } // end A17-N4 sentinel else (body kept unindented to keep the diff reviewable)
            } catch (e: Throwable) {
                // This will catch all exceptions including NoClassDefFoundError and lower-level
                // reflection errors like the one you're experiencing
                println("Unable to process constructors for ${klass.qualifiedName}: ${e.javaClass.name}: ${e.message}")
                ""
            }
        } catch (exception: kotlin.reflect.jvm.internal.KotlinReflectionInternalError) {
            println(exception.toString())
            ""
        } catch (exception: java.lang.IllegalArgumentException) {
            println(exception.toString())
            ""
        } catch (exception: NoClassDefFoundError) {
            println("Missing dependency: ${exception.message}")
            "" // Return empty string when dependency is missing
        } catch (exception: IllegalStateException) {
            println("Likely unable to infer accessibility: ${exception.message}")
            ""
        } catch (exception: Exception) {
            println("Other exceptions happend ${exception.message}")
            ""
        }


        private fun functionsOf(
            klass: KClass<*>,
            interfaceSupertypes: List<KType>,
            typeParameters: List<KTypeParameter>
        ): String {
            val emittedFunctionFqns = mutableSetOf<String>()
            // W19 can re-emit an inherited overload that renders identically to a
            // declared one once distinct Kotlin types collapse to the same TS
            // type (Int/Long -> int). Dedup by the rendered signature line.
            val emittedSignatures = mutableSetOf<String>()
            return try {
            val declaredFns = klass.declaredMemberFunctions.toList()

            // W19: a subclass that redeclares (overrides) one overload of a
            // method shadows the parent's other overloads in TypeScript — a
            // declared `foo(int)` hides an inherited `foo(string)`, making the
            // subclass non-assignable to the parent (TS2416) and dropping the
            // sibling overload at call sites. Re-emit the inherited overloads of
            // any redeclared name so the subclass keeps the full signature set.
            val declaredNames = declaredFns.mapTo(mutableSetOf()) { it.name }
            val coveredSigs = declaredFns.mapTo(mutableSetOf()) { overloadSignature(it) }
            val inheritedOverloads = try {
                klass.memberFunctions.filter {
                    it.name in declaredNames
                        && !it.isAbstract
                        && overloadSignature(it) !in coveredSigs
                }
            } catch (_: Throwable) {
                emptyList<KFunction<*>>()
            }

            // B-fix: a class must structurally satisfy EVERY interface it
            // implements, transitively. Ordinary classes redeclare the abstract
            // interface methods in their own `declaredMemberFunctions`, so the
            // old code re-emitted only the NON-abstract (default) methods of the
            // DIRECT interfaces and relied on that. But Java enums (whose
            // overrides kotlin-reflect does not surface as `declared`) and
            // abstract classes that leave an interface method abstract were left
            // missing those members, so the emitted type was not assignable to
            // the interface (e.g. an enum packet type missing
            // PacketType.direction()/state(); AbstractFloatIterator missing
            // nextFloat). Walk the TRANSITIVE interface closure, compose each
            // interface's type-argument substitution along the chain, and
            // consider ALL of its members (abstract + default).
            val interfaceClosure: List<Pair<KClass<*>, Map<String, KType>>> = run {
                val out = mutableListOf<Pair<KClass<*>, Map<String, KType>>>()
                val seen = mutableSetOf<KClass<*>>()
                fun visit(iface: KClass<*>, subst: Map<String, KType>) {
                    if (!seen.add(iface)) return
                    out.add(iface to subst)
                    val supers = try { iface.supertypes } catch (_: Throwable) { emptyList<KType>() }
                    supers.forEach { st ->
                        val sc = st.classifier as? KClass<*> ?: return@forEach
                        if (!sc.java.isInterface) return@forEach
                        // Resolve this supertype's type args through the current
                        // substitution, then bind the supertype's own params to them.
                        val childSubst = sc.typeParameters.mapIndexedNotNull { index, parameter ->
                            st.arguments.getOrNull(index)?.type?.let { arg ->
                                parameter.name to substituteTypeParameters(arg, subst, fallbackToBound = true)
                            }
                        }.toMap()
                        visit(sc, childSubst)
                    }
                    // Robust fallback: kotlin-reflect's `supertypes` can throw on
                    // (or under-report) some Java interfaces — swallowed above — which
                    // would stop the transitive walk and silently drop a grandparent
                    // interface's members (the real symptom: PacketType.state() two
                    // hops above an enum). Java's interface list is reliable; walk it
                    // too. These carry no substitution, which is fine: only the
                    // non-generic abstract injection consumes the closure, and that
                    // path needs none.
                    val jIfaces = try { iface.java.interfaces } catch (_: Throwable) { emptyArray<Class<*>>() }
                    jIfaces.forEach { ji ->
                        val jk = try { ji.kotlin } catch (_: Throwable) { null } ?: return@forEach
                        visit(jk, emptyMap())
                    }
                }
                interfaceSupertypes.forEach { supertype ->
                    val classifier = supertype.classifier as? KClass<*> ?: return@forEach
                    val subst = classifier.typeParameters.mapIndexedNotNull { index, parameter ->
                        supertype.arguments.getOrNull(index)?.type?.let { parameter.name to it }
                    }.toMap()
                    visit(classifier, subst)
                }
                out
            }

            // Map every interface in the closure to its composed substitution so
            // the emit loop can resolve interface-declared type params (`T`) to
            // the concrete arguments at the implements site. First binding wins
            // on the rare diamond-with-different-args case.
            val supertypeSubstitutions: Map<KClass<*>, Map<String, KType>> = run {
                val m = mutableMapOf<KClass<*>, Map<String, KType>>()
                interfaceClosure.forEach { (iface, subst) -> m.putIfAbsent(iface, subst) }
                m
            }

            // Keep the prior behaviour: emit the DIRECT interfaces' non-abstract
            // (default) methods, substituting the interface's type arguments (W21).
            val directDefaults = interfaceSupertypes.asSequence().flatMap { st ->
                val sc = st.classifier as? KClass<*> ?: return@flatMap emptySequence<KFunction<*>>()
                try { sc.declaredMemberFunctions.asSequence().filter { !it.isAbstract } }
                catch (_: Throwable) { emptySequence() }
            }

            // ADD any interface method the class is missing — abstract OR default —
            // but ONLY from NON-GENERIC interfaces with NON-GENERIC methods.
            // `directDefaults` above only covers DIRECT interfaces, so a DEFAULT
            // method on a TRANSITIVE interface (e.g. PacketType.state() two hops up)
            // would otherwise be dropped; abstract-only filtering misses it too.
            // Restricting to the generic-free subset fixes the common real gap (Java
            // enums / abstract classes missing simple interface methods — PacketType
            // direction()/state(), FloatIterator nextFloat()) WITHOUT re-introducing
            // the generic erasure/variance debt (Comparable<Object> constraint breaks,
            // Function/Predicate covariance) that full transitive emission caused; a
            // non-generic method needs no substitution, so nothing erases to a
            // constraint-violating Object. "Already provided" is matched by name +
            // arity (the interface signature is still unsubstituted here, so a
            // type-aware match would miss a concrete override and emit it twice).
            val declaredNameArity = (declaredFns.asSequence() + inheritedOverloads.asSequence())
                .mapTo(mutableSetOf<Pair<String, Int>>()) { it.name to (it.parameters.size - 1) }
            // Methods the class already provides CONCRETELY through inheritance (a
            // base class), so injecting an interface method of the same name+arity
            // would collide (TS2416/TS2430). Skip them for non-enums. Enums are the
            // exception the whole fix targets: kotlin-reflect does not surface a
            // Java enum's concrete interface impls as members at all, so an empty
            // set here keeps direction()/state() injectable.
            val concreteInherited: Set<Pair<String, Int>> = if (klass.java.isEnum) emptySet() else try {
                klass.memberFunctions.asSequence().filter { !it.isAbstract }
                    .mapTo(mutableSetOf()) { it.name to (it.parameters.size - 1) }
            } catch (_: Throwable) { emptySet() }
            val transitiveNonGeneric = interfaceClosure.asSequence()
                .filter { (iface, _) -> iface.typeParameters.isEmpty() }
                .flatMap { (iface, _) ->
                    try { iface.declaredMemberFunctions.asSequence() }
                    catch (_: Throwable) { emptySequence<KFunction<*>>() }
                }
                .filter { fn ->
                    // SIMPLE signature only: return + every param a concrete,
                    // non-parameterised type. This keeps the real gap (direction():
                    // Direction, nextFloat(): number, getBackend(): X) and excludes
                    // generic-typed members (Optional<...>, Comparable<Object>,
                    // fastutil maps) whose Object-erasure breaks constraints or whose
                    // type arguments don't import.
                    // Also exclude Iterable/Map/array-backed types: the generator
                    // renders them structurally (e.g. `Foo[]`) and does NOT add an
                    // import, so injecting a member that names one yields TS2304
                    // ("cannot find name"). Concrete non-collection types import
                    // normally via dependentTypes.
                    fun simple(t: KType): Boolean {
                        val c = t.classifier
                        return c is KClass<*> && t.arguments.isEmpty() && !shouldIgnoreSuperclass(c)
                    }
                    // PUBLIC only: a protected interface method (clone()/finalize()
                    // off Object, rare protected SAMs) is illegal on a TS interface
                    // (TS1070) and clashes in visibility with an existing overload
                    // (TS2385); consumers can only call the public surface anyway.
                    fn.visibility == KVisibility.PUBLIC
                        && fn.typeParameters.isEmpty()
                        && simple(fn.returnType)
                        && fn.parameters.drop(1).all { simple(it.type) }
                        && (fn.name to (fn.parameters.size - 1)) !in declaredNameArity
                        && (fn.name to (fn.parameters.size - 1)) !in concreteInherited
                }

            (declaredFns.asSequence()
                    + inheritedOverloads.asSequence()
                    + directDefaults
                    + transitiveNonGeneric
                    )
                .let { functionsList ->
                    pipeline.transformFunctionList(functionsList.toList(), klass)
                }.filter { !MIXIN_COUNTER_REGEX.containsMatchIn(it.name) }
                .filter { !SYNTHETIC_MEMBER_REGEX.matches(it.name) }
                .let { fns ->
                    // TS2385: TypeScript forbids overloads of one name from having
                    // mixed visibility. A class with a public covariant override AND
                    // the inherited protected bridge (e.g. `clone(): Excluder` plus
                    // `protected clone(): Object`) renders both -> mixed -> error.
                    // Drop a PROTECTED overload when the same name also has a real
                    // (public/internal) overload: the protected bridge isn't useful
                    // public API and the public override is the real surface.
                    // (PRIVATE overloads render as `// private` comments, so they
                    // don't participate in the visibility clash and are left alone.)
                    val list = fns.toList()
                    val realPublicNames = list.asSequence()
                        .filter { it.visibility != KVisibility.PROTECTED && it.visibility != KVisibility.PRIVATE }
                        .mapTo(mutableSetOf()) { it.name }
                    list.filterNot { it.visibility == KVisibility.PROTECTED && it.name in realPublicNames }
                }
                .sortedWith(compareBy({ it.name }, { it.toString() }))
                .map { function ->
                    val functionName = pipeline.transformFunctionName(function.name, function, klass)

                    // A function reflected off an interface supertype keeps that
                    // interface as its instance-parameter classifier — look up
                    // the supertype's type-argument substitution for it.
                    val substitution = (function.parameters.firstOrNull()?.type?.classifier as? KClass<*>)
                        ?.let { owner -> supertypeSubstitutions[owner] }
                        ?: emptyMap()

                    val returnType = substituteTypeParameters(
                        pipeline.transformFunctionReturnType(function.returnType, function, klass),
                        substitution
                    )
                    val parameterTypes = function.parameters
                        .drop(1)
                        .map { param ->
                            param to substituteTypeParameters(
                                pipeline.transformFunctionParameterType(param.type, param, function, klass),
                                substitution
                            )
                        }
                    // A11: a trailing run of Kotlin-defaulted params whose
                    // @JvmOverloads reduced-arity JVM overloads actually exist in
                    // bytecode renders optional (`radix?: number`). Both gates
                    // (isOptional AND bytecode existence) are required - see the
                    // helper. Suspend functions never qualify (their javaMethod
                    // carries a trailing Continuation, failing the alignment
                    // guard), so no `?` can precede the N2 $completion param
                    // (TS1016 "required after optional" is impossible here).
                    val droppable = jvmDroppableTrailingParams(klass, function)
                    val firstOptional = parameterTypes.size - droppable
                    val parameters = parameterTypes.withIndex().joinToString(", ") { (index, paramPair) ->
                        val (param, paramType) = paramPair
                        // Kotlin vararg -> TS rest param `...name: T[]` (callable with
                        // spread args, matching GraalJS + the LB KDoc examples), but only
                        // when it is genuinely last and renders as an array type (a
                        // non-last vararg -> TS1014, a non-array one -> TS2370).
                        // A17-N2: never for suspend functions - the $completion param
                        // appended below would follow the rest param (TS1014).
                        val rendered = formatKType(paramType).formatWithoutParenthesis()
                        val rest = if (param.isVararg && param === parameterTypes.last().first && rendered.endsWith("[]") && !rendered.contains("=>") && !function.isSuspend) "..." else ""
                        // A rest param cannot also be optional (TS1047); moot in
                        // practice (Kotlin varargs can't have defaults) but cheap.
                        val optional = if (index >= firstOptional && rest.isEmpty()) "?" else ""
                        "${rest}${param.name}${optional}: $rendered"
                    }

                    // Declare every method-level type variable the signature
                    // references — including ones nested inside generic
                    // arguments (`collection: Collection<T>`), which a
                    // top-level-only scan missed, leaving them undeclared.
                    val typeParamsNotOfClass = mutableListOf<KTypeParameter>()
                    collectFreeTypeParameters(returnType, typeParameters, typeParamsNotOfClass)
                    parameterTypes.forEach { (_, paramType) ->
                        collectFreeTypeParameters(paramType, typeParameters, typeParamsNotOfClass)
                    }

                    val visibility = when (function.visibility) {
                        KVisibility.PRIVATE -> "// private "
                        KVisibility.PROTECTED -> "protected "
                        KVisibility.PUBLIC -> ""
                        KVisibility.INTERNAL -> ""
                        else -> ""
                    }


                    // A17-N2: render `suspend` functions honestly. The JVM method is
                    // `name(..., Continuation<? super T>): Object` - the KFunction
                    // view (no continuation, declared return) typed a call form
                    // (`module.enabledEffect()`) that fails at runtime with "no
                    // applicable overload". Append the real trailing parameter and
                    // return `any` (result or COROUTINE_SUSPENDED), matching what
                    // the Java-reflection static path already emits for suspend
                    // bytecode. NOT a Promise (GraalJS does not bridge suspend to
                    // promises) and NOT omitted (the member exists and is callable
                    // with a {context, resumeWith} continuation object).
                    val isSuspend = function.isSuspend
                    val finalParameters = if (isSuspend) {
                        val continuationKType = try {
                            kotlin.coroutines.Continuation::class.createType(
                                listOf(KTypeProjection.invariant(returnType))
                            )
                        } catch (_: Throwable) {
                            // returnType not kotlin-reflect-backed (SyntheticKType):
                            // carry the same shape through the synthetic carrier.
                            SyntheticKType(
                                kotlin.coroutines.Continuation::class,
                                listOf(KTypeProjection.invariant(returnType))
                            )
                        }
                        // formatKType registers the Continuation import via
                        // dependentTypes for free.
                        val renderedContinuation = formatKType(continuationKType).formatWithoutParenthesis()
                        (if (parameters.isEmpty()) "" else "$parameters, ") +
                            "\$completion: $renderedContinuation"
                    } else parameters

                    val formattedReturnType =
                        if (isSuspend) "any" else formatKType(returnType).formatWithoutParenthesis()

                    val typeParamString = formatTypeParameters(typeParamsNotOfClass)

                    RenderedOverload(functionName, visibility, typeParamString, finalParameters, formattedReturnType, isSuspend)
                }
                .let { rendered ->
                    // A12 (pair collapse): the W19 re-emission keys overload
                    // identity on PARAMETERS only, so a nullable declaration and
                    // a non-null one with identical params can coexist (e.g. a
                    // nullable interface default next to a non-null override).
                    // TS resolves same-param overloads by declaration order, so
                    // whichever sorts first silently masks the other - when the
                    // non-null form wins, a null return becomes a runtime landmine.
                    // Collapse such pairs to the NULLABLE form (a spurious null
                    // check is an inconvenience; a masked null is a crash).
                    rendered.filterNot { candidate ->
                        !candidate.returnType.endsWith(" | null") && rendered.any { other ->
                            other !== candidate
                                && other.name == candidate.name
                                && other.visibility == candidate.visibility
                                && other.typeParams == candidate.typeParams
                                && other.parameters == candidate.parameters
                                && (other.returnType == "${candidate.returnType} | null"
                                    || other.returnType == "(${candidate.returnType}) | null")
                        }
                    }
                }
                .joinToString("") { overload ->
                    val signatureLine =
                        "    ${overload.visibility}${overload.name}${overload.typeParams}(${overload.parameters}): ${overload.returnType};"
                    if (!emittedSignatures.add(signatureLine)) {
                        // Identical rendered overload already emitted (W19 dedup).
                        ""
                    } else {
                        val functionDoc = if (emittedFunctionFqns.add("${klass.qualifiedName}.${overload.name}")) {
                            try {
                                kdocSource?.tsdocForFqn("${klass.qualifiedName}.${overload.name}", "    ")
                            } catch (_: Throwable) { null } ?: ""
                        } else ""

                        // A17-N2: one-line remark so the unusual $completion
                        // calling convention is discoverable at the signature.
                        val suspendRemark = if (overload.isSuspend)
                            "    /** Kotlin `suspend` function: pass a Continuation ({ context, resumeWith }) as the final argument; returns the result or COROUTINE_SUSPENDED. */\n"
                        else ""

                        (functionDoc + suspendRemark + signatureLine + "\n").commentIfInvalid()
                    }
                }
            } catch (exception: kotlin.reflect.jvm.internal.KotlinReflectionInternalError) {
                print(exception.toString())
                ""
            } catch (exception: NoClassDefFoundError) {
                print("Missing dependency: ${exception.message}")
                "" // Return empty string when dependency is missing
            } catch (exception: IllegalStateException) {
                print("Likely unable to infer accessibility: ${exception.message}")
                ""
            } catch (exception: Exception) {
                println("Other exceptions happend ${exception.message}")
                ""
            }
        }


        private fun propertiesOf(klass: KClass<*>, interfaceSupertypes: List<KType> = emptyList()): String = try {
            // A3: the property analog of functionsOf's B-fix. An implementer does
            // NOT redeclare a Kotlin interface's default `val` (it is absent from
            // declaredMemberProperties), so the emitted class lacked runtime
            // members like WebSocketEvent.serializer / serializeAsync and was not
            // assignable to its own interface (the bulk of the baselined TS2420s).
            // Same conservative rules as the method injection: NON-GENERIC
            // interfaces only, PUBLIC, simple concrete types (no substitution
            // needed), and skip anything the class declares itself or gets from a
            // base class. Classes only - interfaces inherit via `extends` in TS.
            val injectedInterfaceProperties: List<KProperty1<*, *>> =
                if (klass.java.isInterface || interfaceSupertypes.isEmpty()) emptyList() else try {
                    val declaredNames = klass.declaredMemberProperties.mapTo(mutableSetOf()) { it.name }
                    val fromBaseClass: Set<String> = generateSequence(klass.java.superclass) { it.superclass }
                        .flatMap { sc ->
                            try { sc.kotlin.declaredMemberProperties.map { it.name } }
                            catch (_: Throwable) { emptyList() }
                        }.toSet()
                    val closure = run {
                        val out = mutableListOf<KClass<*>>()
                        val seen = mutableSetOf<KClass<*>>()
                        fun visit(iface: KClass<*>) {
                            if (!seen.add(iface)) return
                            out.add(iface)
                            (try { iface.supertypes } catch (_: Throwable) { emptyList<KType>() })
                                .mapNotNull { it.classifier as? KClass<*> }
                                .filter { it.java.isInterface }
                                .forEach { visit(it) }
                            (try { iface.java.interfaces.toList() } catch (_: Throwable) { emptyList<Class<*>>() })
                                .mapNotNull { try { it.kotlin } catch (_: Throwable) { null } }
                                .forEach { visit(it) }
                        }
                        interfaceSupertypes
                            .mapNotNull { it.classifier as? KClass<*> }
                            .filter { it.java.isInterface }
                            .forEach { visit(it) }
                        out
                    }
                    fun simple(t: KType): Boolean {
                        val c = t.classifier
                        return c is KClass<*> && t.arguments.isEmpty() && !shouldIgnoreSuperclass(c)
                    }
                    val seenNames = mutableSetOf<String>()
                    closure.asSequence()
                        .filter { it.typeParameters.isEmpty() }
                        .flatMap {
                            try { it.declaredMemberProperties.asSequence() }
                            catch (_: Throwable) { emptySequence<KProperty1<*, *>>() }
                        }
                        .filter { p ->
                            p.visibility == KVisibility.PUBLIC
                                && simple(p.returnType)
                                && p.name !in declaredNames
                                && p.name !in fromBaseClass
                                && seenNames.add(p.name)
                        }
                        .toList()
                } catch (_: Throwable) { emptyList() }

            (klass.declaredMemberProperties + injectedInterfaceProperties)
                .filter { !SYNTHETIC_MEMBER_REGEX.matches(it.name) }
                .let { propertyList ->
                    pipeline.transformPropertyList(propertyList.toList(), klass)
                }.sortedWith(compareBy({ it.name }, { it.returnType.toString() }))
                .flatMap { property ->
                    val propertyType = pipeline.transformPropertyType(property.returnType, property, klass)
                    // formatKType now renders function types as arrows (via
                    // kotlinFunctionArrow) AND handles nullability correctly, so
                    // properties go through the same path as params — no separate
                    // formatPropertyFunctionType (which dropped `| null`).
                    val formattedPropertyType = formatKType(propertyType).formatWithoutParenthesis()

                    buildList {
                        // Handle Java Bean properties first
                        if (isJavaBeanProperty(property, klass)) {
                            val transformedPropertyName =
                                pipeline.transformPropertyName(property.name, property, klass)

                            // Check if the property has a private setter
                            val isReadOnly = when {
                                property is KProperty1<*, *> -> {
                                    if (property !is KMutableProperty1<*, *>) {
                                        true
                                    } else {
                                        // Check if setter is private in Kotlin
                                        val isSetterPrivate = property.setter.visibility == KVisibility.PRIVATE
                                        // Fallback to Java reflection if needed
                                        val isJavaSetterPrivate = property.setter.javaMethod?.let { method ->
                                            !Modifier.isPublic(method.modifiers) || Modifier.isPrivate(method.modifiers)
                                        } == true

                                        isSetterPrivate || isJavaSetterPrivate
                                    }
                                }

                                property.javaField != null -> {
                                    Modifier.isFinal(property.javaField!!.modifiers)
                                }

                                else -> false
                            }


                            // Generate as a readonly property if it has a private setter
                            val propFqn = "${klass.qualifiedName}.${property.name}"
                            val propDoc = try {
                                kdocSource?.tsdocForFqn(propFqn, "    ")
                            } catch (_: Throwable) { null } ?: ""
                            if (isReadOnly) {
                                add(
                                    (propDoc + "    readonly ${transformedPropertyName}: $formattedPropertyType;\n")
                                        .commentIfInvalid()
                                )
                            } else {
                                add(
                                    (propDoc + "    ${transformedPropertyName}: $formattedPropertyType;\n").commentIfInvalid()
                                )
                            }

                            // A17-N1: when a METHOD shares the property's name
                            // (TagEntityEvent: `val color` next to `fun color(col,
                            // priority)`), the emitted field+method duo is invalid
                            // TS and F7 (fix-member-collisions.py) resolves it by
                            // DROPPING the field - correct for the bare name
                            // (GraalJS resolves it to the method) but it erased the
                            // property surface entirely: the value is reachable
                            // only via the bean getter, which was never emitted.
                            // Emit the getter (and, for mutable props, setter)
                            // dual so it survives F7. Only the collision case, to
                            // avoid tree-wide getter noise; only the generator can
                            // do this (F7 sees text and can't know a getter exists).
                            //
                            // NAME-based comparison, not identity: getMethods()
                            // returns fresh Method copies per call, so an
                            // `it !== property.javaGetter` guard is dead code (and
                            // silently misfires on @get:JvmName-renamed getters).
                            // The getter is null-checked (never `!!`) and the block
                            // has its own narrow catch: propertiesOf's broad
                            // catches return "", so an exception escaping here
                            // would silently delete EVERY property of the class.
                            try {
                                val collisionGetter = property.javaGetter
                                if (collisionGetter != null && Modifier.isPublic(collisionGetter.modifiers)) {
                                    val shadowedByMethod = klass.java.methods.any { m ->
                                        m.name == property.name
                                            && !(m.name == collisionGetter.name
                                                && m.parameterCount == collisionGetter.parameterCount)
                                    }
                                    if (shadowedByMethod) {
                                        // Regen-triage breadcrumb: enumerate every added
                                        // getter so the baseline review can audit them
                                        // (adversarial-review A17 hole 3).
                                        println("A17-N1: ${klass.qualifiedName}: property '${property.name}' shadowed by a same-name method; emitting ${collisionGetter.name}() dual")
                                        add(
                                            "    ${collisionGetter.name}(): $formattedPropertyType;\n"
                                                .commentIfInvalid()
                                        )
                                        if (property is KMutableProperty1<*, *>) {
                                            val collisionSetter = try { property.javaSetter } catch (_: Throwable) { null }
                                            if (collisionSetter != null && Modifier.isPublic(collisionSetter.modifiers)) {
                                                add(
                                                    "    ${collisionSetter.name}(value: $formattedPropertyType): void;\n"
                                                        .commentIfInvalid()
                                                )
                                            }
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                                // A17-N1 is purely additive - never let it take the
                                // property line (or the class's other properties) down.
                            }
                        } else {
                            // Fallback to existing field/getter generation for non-bean properties
                            // Generate field entry if javaField exists
                            val javaField = property.javaField
                            if (javaField != null) {
                                val transformedFieldName =
                                    pipeline.transformPropertyName(property.name, property, klass)
                                val visibility =
                                    if (Modifier.isPublic(javaField.modifiers)) "" else "// private "
                                val fieldDoc = try {
                                    kdocSource?.tsdocForFqn("${klass.qualifiedName}.${property.name}", "    ")
                                } catch (_: Throwable) { null } ?: ""
                                add(
                                    (fieldDoc + "    ${visibility}${transformedFieldName}: $formattedPropertyType;\n")
                                        .commentIfInvalid()
                                )
                            }

                            // Generate getter function entry if javaGetter exists and not already handled as bean
                            property.javaGetter?.let { javaGetter ->
                                val transformedGetterName = javaGetter.toKFunction()?.let { func ->
                                    pipeline.transformFunctionName(javaGetter.name, func, klass)
                                } ?: "/*not mapped: */ ${javaGetter.name}"

                                val visibility = if (Modifier.isPublic(javaGetter.modifiers)) "" else "// private "
                                add(
                                    "    ${visibility}${transformedGetterName}(): $formattedPropertyType;\n"
                                        .commentIfInvalid()
                                )
                            }
                        }
                    }
                }.joinToString("")
        } catch (exception: kotlin.reflect.jvm.internal.KotlinReflectionInternalError) {
            print(exception.toString())
            "" // Return empty string when an internal reflection error occurs
        } catch (exception: NoClassDefFoundError) {
            print("Missing dependency: ${exception.message}")
            "" // Return empty string when dependency is missing
        } catch (exception: IllegalStateException) {
            print("Likely unable to infer accessibility: ${exception.message}")
            ""
        } catch (exception: Exception) {
            println("Other exceptions happend ${exception.message}")
            ""
        }


        /**
         * A15 (nashorn dual surface, Java side): GraalJS nashorn-compat exposes a
         * Java getter BOTH as the method (`mc.getConnection()`) and as the bean
         * property (`mc.connection`). kotlin-reflect only synthesizes a Java
         * "property" when a BACKING FIELD with the bean name exists, so
         * field-less getters were method-only and the idiomatic property read
         * was a type error. Emit the property form for declared public instance
         * getters whose bean name is otherwise unused.
         *
         * Guards - each avoids a concrete TS error class:
         *  - only CLASSES: injecting into an interface would oblige every
         *    implementer to carry the property (TS2420 debt, the A3 class);
         *  - skip when the bean name is any PUBLIC METHOD name in the hierarchy
         *    (a property overriding an inherited method is TS2416; a local
         *    field+method of one name is fix-member-collisions.py's domain);
         *  - skip when a property/field of that name already renders here or in
         *    a base class or arrives via the A3 interface-property injection
         *    (TS2300 duplicate identifier);
         *  - Kotlin classes are skipped wholesale: their properties already
         *    render as properties. The reverse dual (`getEventManager()` next to
         *    `eventManager`) is intentionally NOT emitted - it would add a line
         *    for every Kotlin property tree-wide for a purely legacy calling
         *    convention; documented in typings/README.md instead.
         */
        @Suppress("unused")  // A15 disabled at the call site; kept for a future whole-hierarchy-aware attempt.
        private fun nashornBeanPropertiesOf(klass: KClass<*>): String = try {
            if (klass.java.isInterface
                || klass.java.getAnnotation(Metadata::class.java) != null
            ) ""
            else {
                val declaredPropertyNames = try {
                    klass.declaredMemberProperties.mapTo(mutableSetOf()) { it.name }
                } catch (_: Throwable) { mutableSetOf<String>() }
                // Public fields anywhere in the hierarchy render (or inherit) as
                // properties already.
                val fieldNames = try {
                    klass.java.fields.mapTo(mutableSetOf()) { it.name }
                } catch (_: Throwable) { mutableSetOf<String>() }
                // Any public method name in the hierarchy: property-vs-method is
                // a TS2416 across extends and an F7 collision within the class.
                val methodNames = try {
                    klass.java.methods.mapTo(mutableSetOf()) { it.name }
                } catch (_: Throwable) { mutableSetOf<String>() }
                // Base-class properties (a private field + getter in a base class
                // already emits the bean property THERE; the subclass inherits it).
                val basePropertyNames = generateSequence(klass.java.superclass) { it.superclass }
                    .flatMap { superclass ->
                        try { superclass.kotlin.declaredMemberProperties.map { it.name } }
                        catch (_: Throwable) { emptyList() }
                    }.toSet()
                // A3-injected interface default properties (transitive closure).
                val interfacePropertyNames = run {
                    val seen = mutableSetOf<Class<*>>()
                    val names = mutableSetOf<String>()
                    fun visit(c: Class<*>) {
                        c.interfaces.forEach { iface ->
                            if (seen.add(iface)) {
                                try {
                                    iface.kotlin.declaredMemberProperties.forEach { names.add(it.name) }
                                } catch (_: Throwable) { /* keep walking */ }
                                visit(iface)
                            }
                        }
                        c.superclass?.let(::visit)
                    }
                    try { visit(klass.java) } catch (_: Throwable) { /* best effort */ }
                    names
                }
                val taken = declaredPropertyNames + fieldNames + basePropertyNames + interfacePropertyNames

                // Class-level type variables stay valid in an instance member -
                // map them to themselves so `getFoo(): T` renders as `foo: T`.
                val classTypeParamSelfMap = klass.typeParameters.associate { it.name to it.createType() }

                klass.java.declaredMethods
                    .filter {
                        Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
                            && !it.isSynthetic && !it.isBridge
                            && it.parameterCount == 0 && it.returnType != Void.TYPE
                            && it.typeParameters.isEmpty()
                            // Object.getClass() would inject `class: Class<Object>`
                            // into Object (hence EVERY class) and pull the whole
                            // java.lang.Class graph into every module - reflection
                            // noise, not script surface (Java.type handles carry
                            // `.class` via the ambient A10/F10 helper instead).
                            && it.name != "getClass"
                            && !MIXIN_COUNTER_REGEX.containsMatchIn(it.name)
                            && !SYNTHETIC_MEMBER_REGEX.matches(it.name)
                    }
                    .mapNotNull { getter ->
                        val n = getter.name
                        val beanName = when {
                            n.length > 3 && n.startsWith("get") && n[3].isUpperCase() ->
                                Introspector.decapitalize(n.substring(3))
                            n.length > 2 && n.startsWith("is") && n[2].isUpperCase()
                                && getter.returnType == java.lang.Boolean.TYPE ->
                                Introspector.decapitalize(n.substring(2))
                            else -> null
                        } ?: return@mapNotNull null
                        if (beanName in taken || beanName in methodNames) return@mapNotNull null
                        beanName to getter
                    }
                    // Sort BEFORE distinctBy: getDeclaredMethods order is
                    // JVM-dependent, and two getters can map to one bean name
                    // (`getFoo()` + `isFoo()`) - the survivor must be stable.
                    .sortedWith(compareBy({ it.first }, { it.second.name }))
                    .distinctBy { it.first }
                    .joinToString("") { (beanName, getter) ->
                        val rendered = formatKType(
                            javaTypeToKotlinType(getter.genericReturnType, classTypeParamSelfMap)
                        ).formatWithoutParenthesis()
                        // Writable only when a matching public setter exists
                        // (nashorn maps `x = v` to setX(v)).
                        val setterName = "set" + getter.name.removePrefix("get").removePrefix("is")
                        val hasSetter = try {
                            klass.java.methods.any {
                                it.name == setterName && it.parameterCount == 1
                                    && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
                            }
                        } catch (_: Throwable) { false }
                        val readonly = if (hasSetter) "" else "readonly "
                        "    $readonly$beanName: $rendered;\n".commentIfInvalid()
                    }
            }
        } catch (_: Throwable) {
            ""
        }


        private fun formatPropertyFunctionType(type: KType): String {
            val arguments = type.arguments.dropLast(1) // Drop the return type
            val returnType =
                type.arguments.lastOrNull()?.type?.let { formatKType(it).formatWithoutParenthesis() } ?: "void"

            val parameters = arguments.mapIndexed { index, arg ->
                val paramType = formatKType(arg.type ?: return@mapIndexed "any")
                "param$index: ${paramType.formatWithoutParenthesis()}"
            }.joinToString(", ")

            // Return the TypeScript function type
            return "($parameters) => $returnType"
        }


        fun isFunctionalInterface(javaType: Type): Boolean {

            if (javaType is Class<*>) {
                // Check for @FunctionalInterface annotation
                if (javaType.isAnnotationPresent(FunctionalInterface::class.java)) {
                    return true
                }


                // Check if it's an interface with exactly one abstract method
                // not enabling that for the moment
//                if (javaType.isInterface) {
//                    val abstractMethods = javaType.methods.filter {
//                        Modifier.isAbstract(it.modifiers) && !it.isDefault && !Modifier.isStatic(it.modifiers)
//                    }
//                    return abstractMethods.size == 1
//                }
            }

            // Handle ParameterizedType (e.g., Consumer<String>)
            if (javaType is ParameterizedType) {
                return isFunctionalInterface(javaType.rawType)
            }

            return false
        }


        /**
         * Finds the single abstract method in a functional interface
         */
        fun findSingleAbstractMethod(javaType: Type): Method? {
            val clazz = when (javaType) {
                is Class<*> -> javaType
                is ParameterizedType -> javaType.rawType as Class<*>
                else -> return null
            }

            return clazz.methods.find {
                Modifier.isAbstract(it.modifiers) && !it.isDefault && !Modifier.isStatic(it.modifiers)
                    // JLS 9.8: methods matching a public java.lang.Object method
                    // don't count toward the SAM. java.util.Comparator redeclares
                    // equals(Object) abstract; without this filter it wins over
                    // compare(T,T) (clazz.methods order) and every comparator
                    // rendered as `(param0: Object) => boolean`.
                    && !isPublicObjectMethod(it)
            }
        }

        /** True for signatures that match a public method of java.lang.Object. */
        private fun isPublicObjectMethod(m: Method): Boolean = when (m.name) {
            "equals" -> m.parameterCount == 1 && m.parameterTypes[0] == java.lang.Object::class.java
            "hashCode", "toString" -> m.parameterCount == 0
            else -> false
        }

        /**
         * Maps a functional interface type to a TypeScript arrow type.
         *
         * The SAM signature comes from Java reflection, but every type in it is
         * converted to a KType ([javaTypeToKotlinType]) and rendered through
         * [formatKType]. The previous implementation string-interpolated the
         * fallback return type as a raw KType (its `toString()` — e.g.
         * `kotlin.Boolean`, `java.util.Optional<...Resource>`), bypassing both
         * the primitive mappings and the dependentTypes/tsNameFor import
         * machinery.
         *
         * The interface's declared type variables are substituted with the
         * actual type arguments at this use site (`Predicate<String>` renders
         * its SAM `test(T)` as `(param0: string) => boolean`); unresolvable
         * variables fall back to their bound via [javaTypeToKotlinType].
         */
        fun formatFunctionalInterfaceType(type: Type, kType: KType?): String {
            val sam = findSingleAbstractMethod(type) ?: return "Function"

            val rawClass = when (type) {
                is Class<*> -> type
                is ParameterizedType -> type.rawType as? Class<*>
                else -> null
            }

            // Break SAM self-reference cycles with the nominal type name
            // (formatKType with transformFunctionalInterface=false). Strip the
            // nullable marker — the caller appends `| null` itself.
            if (rawClass != null && rawClass in functionalInterfacesBeingRendered) {
                return kType
                    ?.let { formatKType(it.withNullability(false), false, false).formatWithoutParenthesis() }
                    ?: "Function"
            }

            rawClass?.let { functionalInterfacesBeingRendered.add(it) }
            try {
                // Resolve the interface's type variables from the actual type
                // arguments at this use site (positional, by declaration order).
                val baseSubstitution: Map<String, KType> =
                    if (kType != null && rawClass != null) {
                        rawClass.typeParameters.mapIndexedNotNull { index, typeVariable ->
                            kType.arguments.getOrNull(index)?.type?.let { typeVariable.name to it }
                        }.toMap()
                    } else emptyMap()

                // A17: the SAM may be DECLARED on a generic SUPERinterface whose
                // type variables differ from the raw class's (UnaryOperator<T>
                // declares nothing itself; its SAM is Function<T, T>.apply(T): R).
                // Substituting only the raw class's variables left Function's `R`
                // to eraseToBound - `UnaryOperator<Component>` rendered as
                // `(param0: Component) => Object | null` instead of
                // `(param0: Component) => Component`. Re-key the substitution
                // onto the SAM's declaring class by composing the generic
                // supertype arguments along the inheritance path.
                val substitution: Map<String, KType> =
                    if (rawClass != null && sam.declaringClass != rawClass)
                        composeSubstitutionFor(rawClass, baseSubstitution, sam.declaringClass)
                            ?: baseSubstitution
                    else baseSubstitution

                val parameters = sam.parameters.mapIndexed { index, param ->
                    val paramType = javaTypeToKotlinType(param.parameterizedType, substitution)
                    "param$index: ${formatKType(paramType).formatWithoutParenthesis()}"
                }.joinToString(", ")

                val returnType = if (sam.returnType == Void.TYPE) {
                    "void"
                } else {
                    formatKType(javaTypeToKotlinType(sam.genericReturnType, substitution))
                        .formatWithoutParenthesis()
                }

                return "($parameters) => $returnType"
            } finally {
                rawClass?.let { functionalInterfacesBeingRendered.remove(it) }
            }
        }

        /**
         * Walks the generic supertype graph from [from] to [target], composing
         * type-argument substitutions edge by edge, and returns a substitution
         * keyed by [target]'s OWN type-variable names (or null if [target] is
         * not reachable). Used to resolve a SAM declared on a superinterface
         * of the functional interface actually referenced (see A17 above).
         */
        private fun composeSubstitutionFor(
            from: Class<*>,
            substitution: Map<String, KType>,
            target: Class<*>
        ): Map<String, KType>? {
            if (from == target) return substitution
            val supertypes = from.genericInterfaces.asList() + listOfNotNull(from.genericSuperclass)
            for (supertype in supertypes) {
                val (raw, args) = when (supertype) {
                    is ParameterizedType ->
                        (supertype.rawType as? Class<*> ?: continue) to supertype.actualTypeArguments.toList()
                    is Class<*> -> supertype to emptyList()
                    else -> continue
                }
                val next = raw.typeParameters.mapIndexed { index, typeVariable ->
                    typeVariable.name to (args.getOrNull(index)
                        ?.let { try { javaTypeToKotlinType(it, substitution) } catch (_: Throwable) { KotlinAnyOrNull } }
                        ?: KotlinAnyOrNull)
                }.toMap()
                composeSubstitutionFor(raw, next, target)?.let { return it }
            }
            return null
        }

    }

    private val modules = mutableMapOf<KClass<*>, GeneratedModule>()

    // O(1) by-name index mirroring [modules], keyed exactly as isSameClass
    // compares (qualifiedName; all null-qualifiedName classes share one bucket,
    // matching the old `null == null` behavior of the linear scans this replaces).
    // The previous code located a class's module with `modules.keys.find { isSameClass }`
    // -- an O(n) scan per call, run once per class on the visited-check AND per
    // dependent during import resolution, i.e. O(n^2) over ~57k classes (and it
    // recomputed qualifiedName on every comparison). This index makes both O(1).
    private val modulesByName = HashMap<String, GeneratedModule>()

    private fun moduleKeyOf(klass: KClass<*>): String = klass.qualifiedName ?: NULL_QUALIFIED_NAME

    private fun putModule(klass: KClass<*>, module: GeneratedModule) {
        modules[klass] = module
        modulesByName.putIfAbsent(moduleKeyOf(klass), module)
    }

    // Optional persistent render cache (ModuleCache). Enabled only when the env
    // var TSGEN_CACHE_DIR is set (the regen sets it); off for normal test/embed
    // runs. Lets foundational-library classes from unchanged jars skip reflection
    // by reusing the prior run's rendered .d.ts. See ModuleCache for soundness.
    private val moduleCache: ModuleCache? = run {
        val dir = System.getProperty("tsgen.cacheDir") ?: System.getenv("TSGEN_CACHE_DIR")
        if (dir.isNullOrBlank()) null
        else ModuleCache.open(
            java.io.File(dir),
            try { TypeScriptGenerator::class.java.protectionDomain?.codeSource?.location } catch (t: Throwable) { null }
        )
    }

    private val pipeline = ClassTransformerPipeline(classTransformers)

    private val ignoredSuperclasses = setOf<KClass<*>>(
    ).plus(ignoreSuperclasses)

    private val predefinedMappings =
        mapOf(
            Boolean::class to "boolean",
            String::class to "string",
            Char::class to "string",

            Int::class to intTypeName,
            Long::class to intTypeName,
            Short::class to intTypeName,
            Byte::class to intTypeName,

            Float::class to "number",
            Double::class to "number",

            Unit::class to "void",
            Void::class to "void",

            // Kotlin range/progression types — all implement Iterable, so without
            // explicit mappings they collapse through arrayFromKType to (Object|null)[].
            // Map them to inline structural shapes that capture the primary consumer API.
            IntRange::class to "{ start: number; endInclusive: number; step: number }",
            LongRange::class to "{ start: number; endInclusive: number; step: number }",
            CharRange::class to "{ start: string; endInclusive: string; step: number }",
            IntProgression::class to "{ first: number; last: number; step: number }",
            LongProgression::class to "{ first: number; last: number; step: number }",
            CharProgression::class to "{ first: string; last: string; step: number }",

            ).plus(mappings) // mappings has a higher priority

    // Defensive index by qualified name — KClass equality across classloaders
    // (in particular our shadowJar vs LB's runtime kotlin-reflect) can fall
    // back to identity-based equality and miss the primary map. Looking up
    // by qualified name catches Unit/Void from any source.
    private val predefinedMappingsByName: Map<String, String> =
        predefinedMappings
            .mapNotNull { (k, v) -> k.qualifiedName?.let { it to v } }
            .toMap()

    // A type that implements `Iterable<Self>` (e.g. java.nio.file.Path : Iterable<Path>)
    // is a real class that merely happens to be iterable, NOT a collection. Treating it
    // as one (no module, rendered as the nominal `Path[]`) leaves the name undefined
    // (TS2304), because no `Path.d.ts` is emitted to import. Detect the self-iterating
    // shape so such a type keeps a module and gets imported.
    private fun isSelfIterable(klass: KClass<*>): Boolean = try {
        klass.supertypes
            .firstOrNull { it.classifier == Iterable::class }
            ?.arguments?.firstOrNull()?.type?.classifier
            ?.let { it is KClass<*> && isSameClass(it, klass) } == true
    } catch (_: Throwable) { false }

    private val shouldIgnoreSuperclass: (KClass<*>) -> Boolean = { klass: KClass<*> ->
        try {
            if (klass.objectInstance != null) {
                // Kotlin object singleton — emit fully; don't route through array/map paths
                false
            } else {
                val array = klass.javaObjectType.isArray
                val iterable = Iterable::class.java.isAssignableFrom(klass.java) && !isSelfIterable(klass)
                val map = Map::class.java.isAssignableFrom(klass.java)
                iterable || array || map
            }
        } catch (throwable: Throwable) {
            println("Error in shouldIgnoreSuperclass: ${throwable.message}")
            false
        }
    }


    init {
        rootClasses.forEach {
            visitClass(it)

            try {
                val nestedClasses = it.nestedClasses

                nestedClasses.forEach { klass ->
                    try {
                        visitClass(klass)
                    } catch (throwable: Throwable) {
                        print("Skipping nested class $klass for $it, exception occurred: ${throwable.message}")
                    }
                }

            } catch (throwable: Throwable) {
                print("Skipping all nested class for $it, exception occurred: ${throwable.message})")
            }
        }

        // Persist the render cache for next time. Store every freshly-rendered
        // module (reused ones were already carried forward inside
        // ModuleCache.tryReuse). Runs after the whole walk so each moduleText
        // resolves its imports against a fully-populated module set.
        moduleCache?.let { mc ->
            modules.forEach { (klass, mod) ->
                if (mod is TypeScriptModule) {
                    mc.recordFresh(
                        klass,
                        mod.path,
                        mod.dependentTypes.mapNotNull { it.java.name },
                        mod.moduleText
                    )
                }
            }
            mc.flush()
        }
    }

    companion object {
        // Shared key for classes with no qualifiedName (local/anonymous). The old
        // linear scans treated all such classes as "the same" (null == null); this
        // preserves that. No real class is named "\u0000NULL".
        private const val NULL_QUALIFIED_NAME = "\u0000NULL"

        private val KotlinAnyOrNull = Any::class.createType(nullable = true)
        private val KotlinNotNull = Any::class.createType(nullable = false)

        // A13: path of the synthetic JavaMap module at the types/ root. Root
        // placement keeps the import path computation identical to the
        // existing dependent-type imports ("../".repeat(depth) + this).
        internal const val JAVA_MAP_MODULE_PATH = "JavaMap.d.ts"

        // A13: hand-authored declaration for the GraalJS host surface of a
        // java.util.Map instance. Member set + nullability mirror the
        // runtime-verified F8 localStorage facade (ScriptLocalStorage in
        // typings/ambient/ambient.d.ts), typed K/V instead of `any`.
        // keySet()/values()/entrySet() stay `any`, NOT arrays: GraalJS
        // array-maps java.util.List and Java arrays but NOT the live
        // Set/Collection views these return (no .length, no index access) —
        // typing them K[]/V[] would be a new runtime lie, the exact class
        // A13 exists to remove. No bean-property duals (the A15 cascade) and
        // no JavaMap${'$'}Entry sibling (entrySet is `any`, so none is needed).
        internal val JAVA_MAP_MODULE_TEXT = """
            |import type { Object } from './java/lang/Object.d.ts'
            |
            |/**
            | * A13: the GraalJS host surface of a `java.util.Map` instance. Method calls
            | * only: NO bracket indexing (`m["k"]` reads a member, not an entry), NO
            | * JS-`Map` API (`.set`, `.size` as a property), NO `for...of` / spread.
            | */
            |export interface JavaMap<K, V> extends Object {
            |    /** Value for `key`, or `null` if absent. */
            |    get(key: K): V | null;
            |    /** Stores `value`; returns the previous value or `null`. */
            |    put(key: K, value: V): V | null;
            |    /** Stores only if absent; returns the existing value or `null`. */
            |    putIfAbsent(key: K, value: V): V | null;
            |    getOrDefault(key: K, defaultValue: V): V;
            |    /** Removes `key`; returns the removed value or `null`. */
            |    remove(key: K): V | null;
            |    containsKey(key: K): boolean;
            |    clear(): void;
            |    size(): number;
            |    isEmpty(): boolean;
            |    /** Computes a value for `key` (creating it if absent). */
            |    compute(key: K, remap: (key: K, value: V | null) => V | null): V | null;
            |    /** Computes and stores a value only if `key` is absent. */
            |    computeIfAbsent(key: K, mapping: (key: K) => V): V;
            |    /** Merges `value` into the existing value for `key`. */
            |    merge(key: K, value: V, remap: (oldValue: V, value: V) => V | null): V | null;
            |    /** Replaces the value for `key` only if it is currently present. */
            |    replace(key: K, value: V): V | null;
            |    /** Runs `action` for every entry. NOTE: Java argument order `(key, value)` - the OPPOSITE of JS `Map#forEach`. */
            |    forEach(action: (key: K, value: V) => void): void;
            |    /** The key set (a live Java Set view - not array-mapped by GraalJS). */
            |    keySet(): any;
            |    /** The entry set (a live Java Set view - not array-mapped by GraalJS). */
            |    entrySet(): any;
            |    /** The values collection (a live Java Collection view - not array-mapped by GraalJS). */
            |    values(): any;
            |}
            |""".trimMargin()

        /** Overload identity: name + value-parameter types (drops the receiver).
         * Used by W19 to tell an override from a distinct sibling overload. */
        private fun overloadSignature(f: KFunction<*>): String =
            f.name + "(" + f.parameters.drop(1).joinToString(",") { it.type.toString() } + ")"

        /**
         * A11: how many trailing parameters of [function] can be omitted at the
         * JVM level, i.e. a @JvmOverloads reduced-arity overload exists in
         * bytecode for every arity down to n - result. 0 disables the feature
         * for this function.
         *
         * Double gate — BOTH halves are required:
         *  - `isOptional` alone would be WRONG: a Kotlin default WITHOUT
         *    @JvmOverloads produces no reduced-arity JVM method (only the
         *    filtered `$default` bridge); GraalJS matches host calls by arity,
         *    so blanket `?` would type-launder real runtime errors (every data
         *    class `copy()` must stay full-arity).
         *  - bytecode existence alone would fire on every hand-written
         *    telescoping Java overload pair tree-wide, churning thousands of
         *    lines for zero information (the shorter overload already emits as
         *    its own signature).
         * The same-return prefix-typed match kills the dangerous false positive
         * (a hand-written reduced-arity overload with a DIFFERENT return must
         * keep resolving to its own emitted signature).
         *
         * The alignment guard (`parameterCount != valueParams.size`) also
         * excludes suspend functions (their javaMethod carries a trailing
         * Continuation) and receiver-shifted shapes (member extension fns).
         */
        private fun jvmDroppableTrailingParams(klass: KClass<*>, function: KFunction<*>): Int {
            val valueParams = function.parameters.drop(1)          // matches the render loop
            if (valueParams.none { it.isOptional }) return 0        // fast path: no defaults
            val jm = try { function.javaMethod } catch (_: Throwable) { null } ?: return 0
            if (jm.parameterCount != valueParams.size) return 0
            val fullTypes = jm.parameterTypes
            val methods = try { klass.java.methods } catch (_: Throwable) { return 0 }
            var droppable = 0
            for (i in valueParams.indices.reversed()) {
                if (!valueParams[i].isOptional) break                // only a contiguous trailing run
                val reducedArity = i
                val exists = methods.any { m ->
                    m.name == jm.name
                        && m.parameterCount == reducedArity
                        && m.returnType == jm.returnType
                        && !m.isSynthetic
                        && m.parameterTypes.contentEquals(fullTypes.copyOfRange(0, reducedArity))
                }
                if (!exists) break
                droppable++
            }
            return droppable
        }

        /** A11 (constructor rider): same double gate as
         * [jvmDroppableTrailingParams], against `klass.java.constructors` —
         * `new Color4b(r, g, b)` (a=255 default via `@JvmOverloads
         * constructor`) was a shipped type error. No return-type guard needed
         * (all ctors "return" the class); synthetic ctors (default-args
         * bridges, N4) are excluded from the existence check. */
        private fun jvmDroppableTrailingCtorParams(klass: KClass<*>, constructor: KFunction<*>): Int {
            val valueParams = constructor.parameters                // ctor KFunctions have no receiver
            if (valueParams.none { it.isOptional }) return 0
            val jc = try { constructor.javaConstructor } catch (_: Throwable) { null } ?: return 0
            if (jc.parameterCount != valueParams.size) return 0     // e.g. inner-class outer ref
            val fullTypes = jc.parameterTypes
            val ctors = try { klass.java.constructors } catch (_: Throwable) { return 0 }
            var droppable = 0
            for (i in valueParams.indices.reversed()) {
                if (!valueParams[i].isOptional) break
                val exists = ctors.any { c ->
                    c.parameterCount == i
                        && !c.isSynthetic
                        && c.parameterTypes.contentEquals(fullTypes.copyOfRange(0, i))
                }
                if (!exists) break
                droppable++
            }
            return droppable
        }
        // Matches kotlin.Function0 .. kotlin.Function22 (the arity-specific
        // function-type classes kotlin-reflect reports as the classifier).
        private val FUNCTION_CLASS_NAME = Regex("""kotlin\.Function\d+""")

        // Mixin-injected synthetic methods have non-deterministic counter segments in their names.
        // Two formats observed:
        //   - Inline:  handler$zig000$plugin$target  → counter between dollar signs ($zig000$)
        //   - Prefix:  md3d0a70$plugin$target        → counter at start before first dollar sign
        // Filter either format; both are non-deterministic across JVM runs.
        private val MIXIN_COUNTER_REGEX = Regex("""(^[a-z]{1,3}[0-9a-f]{4,8}\$|\$([a-z]{1,3}[0-9a-f]{3,4}|[0-9a-f]{6})\$)""")

        // W-#10: Kotlin compiler synthetic members. These appear in declaredMember*
        // reflection but exist purely for compiler-internal bookkeeping — emitting
        // them as part of the public TypeScript surface is noise that pollutes
        // autocomplete (e.g. `ClientModule.d.ts`: 33/131 synthetic members).
        //
        //   access${name}$jd / access${name}$cp / access${name}      — companion-class accessors
        //   {name}$default                                            — default-args bridge
        //   {name}$annotations                                        — synthetic annotation holder
        //   ${prefix}$inlined${suffix}                                 — inline-function lambda capture
        //   {name}$suspendImpl                                         — open-suspend-fn static bridge (A17-N2)
        //
        // Keep tight: only filter names that match the recognised compiler-synthetic
        // shape, not arbitrary names that happen to contain `$`.
        private val SYNTHETIC_MEMBER_REGEX = Regex(
            "^(access[\$].*|.*[\$]default|.*[\$]annotations|.*[\$]inlined[\$].*|.*[\$]suspendImpl)$"
        )

        fun isJavaBeanProperty(kProperty: KProperty<*>, klass: KClass<*>): Boolean {
            val beanInfo = Introspector.getBeanInfo(klass.java)
            return beanInfo.propertyDescriptors
                .any { bean -> bean.name == kProperty.name }
        }

    }

    private fun isSameClass(klassLhs: KClass<*>, klassRhs: KClass<*>): Boolean =
        klassLhs.qualifiedName == klassRhs.qualifiedName

    private fun visitClass(klass: KClass<*>) {
        if (predefinedMappings.containsKey(klass)
            || (klass.qualifiedName != null
                && predefinedMappingsByName.containsKey(klass.qualifiedName))) {
            // Don't emit a module file for any class we substitute inline
            // (e.g. Unit/Void → "void", primitives → "number"/"boolean").
            return
        }
        if (ignoredSuperclasses.count {
                isSameClass(
                    klass,
                    it
                )
            } > 0 || modulesByName.containsKey(moduleKeyOf(klass)))
            return

        // A14: collection-shaped classes (Iterable/Map/array-backed) used to be
        // skipped outright - correct for their INSTANCES (rendered structurally,
        // never imported) but it also dropped their STATIC factories
        // (ImmutableList.of/copyOf, List.of, TreeMap statics ...) from the
        // Java.type()/registry surface. Emit a statics-only module instead;
        // classes without statics still produce nothing.
        val staticsOnly = shouldIgnoreSuperclass(klass)
        if (staticsOnly) {
            try {
                if (klass.javaObjectType.isArray) return // arrays: no statics
            } catch (_: Throwable) {
                return
            }
        }

        // Per-class resilience: a single class whose reflection fails (e.g. Kotlin
        // 2.4.0's reflect throws KotlinReflectionInternalError "could not compute
        // caller" for some abstract operator funs) must NOT abort the whole run --
        // skip just that class. Without this an uncaught error in generateInterface
        // (e.g. at `klass.typeParameters`) on a single ROOT class produced an empty
        // output ("Array is empty").
        // Cache short-circuit: reuse the prior run's rendered module for a
        // foundational class whose source jar (and the generator) are unchanged.
        // No reflection happens; we still re-enqueue the cached dependent types
        // so any type reachable ONLY through this class is still emitted.
        moduleCache?.tryReuse(klass)?.let { reused ->
            putModule(klass, CachedModule(reused.path, reused.moduleText))
            reused.deps.forEach { visitClass(it) }
            return
        }

        val module = try {
            TypeScriptModule(klass, staticsOnly)
        } catch (t: Throwable) {
            println("Skipping ${klass.qualifiedName ?: klass}: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        // A14: a statics-only candidate with no static surface emits nothing.
        if (module.definition.isBlank()) return
        putModule(klass, module)
        module.dependentTypes.forEach { visitClass(it) }
    }


    private fun isFunctionType(javaType: Type): Boolean {
        return javaType is KCallable<*>
                || javaType.typeName.startsWith("kotlin.jvm.functions.")
                || (javaType is ParameterizedType && isFunctionType(javaType.rawType))
    }


    // Public API:

    /** Number of modules served from the persistent cache (0 if cache off). */
    @Suppress("unused")
    val cacheReuseCount: Int
        get() = moduleCache?.reused ?: 0

    @Suppress("unused")
    val definitionsAsModules: Map<String, String>
        get() {
            // A13: the synthetic JavaMap module is emitted UNCONDITIONALLY,
            // not only when some module referenced it — with a warm
            // ModuleCache, reused moduleTexts carry their
            // `import type { JavaMap } ...` line verbatim while no fresh
            // module sets usesJavaMap, so the import target must always
            // exist. Guard: no real class named JavaMap exists in the
            // current jar set; if a default-package one ever appears, its
            // module wins the map merge below (later entry) — log loudly so
            // the collision is investigated instead of silently shipped.
            modules.values.firstOrNull { it.path == JAVA_MAP_MODULE_PATH }?.let {
                System.err.println(
                    "WARNING (A13): a generated module claimed $JAVA_MAP_MODULE_PATH — " +
                        "it shadows the synthetic JavaMap module; every JavaMap import is now wrong"
                )
            }
            return mapOf(JAVA_MAP_MODULE_PATH to JAVA_MAP_MODULE_TEXT) +
                modules.map { it.value.path to it.value.moduleText }
        }

    @Suppress("unused")
    val definitionsText: String
        get() = modules.map { it.value.definition }.joinToString("\n\n")

    @Suppress("unused")
    val individualDefinitions: Set<String>
        get() = modules.map { it.value.definition }.toSet()
}