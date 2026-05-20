package me.commandblock2.tsGenerator

import kotlin.reflect.KFunction
import kotlin.reflect.jvm.kotlinFunction
import java.lang.reflect.Method
import kotlin.reflect.jvm.javaType

fun Method.toKFunction(): KFunction<*>? {
    // Try to get the Kotlin function directly if this is a Kotlin-generated method
    kotlinFunction?.let { return it }

    // For pure Java methods, we need to search the declaring class's Kotlin functions
    val kClass = declaringClass.kotlin
    return kClass.members.filterIsInstance<KFunction<*>>()
        .firstOrNull {
            it.name == name &&
                    it.parameters.size == parameterCount + 1 && // +1 for receiver parameter
                    it.parameters.drop(1).zip(parameterTypes.toList()).all { (param, javaType) ->
                        param.type.javaType == javaType
                    }
        } as? KFunction<*>
}
