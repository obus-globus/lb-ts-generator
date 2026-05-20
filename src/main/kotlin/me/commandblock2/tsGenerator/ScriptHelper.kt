/*
 * Helper bridge invoked by ts-defgen.js from inside LiquidBounce's GraalVM
 * polyglot context. Wraps `@CallerSensitive` JDK methods (Thread.getContextClassLoader,
 * Class.forName, etc.) behind static helpers so the caller-class seen by the
 * JDK is this Kotlin class (loaded on the Knot mod classloader) instead of
 * GraalVM's "restricted lookup object" — the latter triggers
 * `IllegalAccessException: Attempt to lookup caller-sensitive method using
 * restricted lookup object` under Java 25.
 */
package me.commandblock2.tsGenerator

import com.google.common.reflect.ClassPath

object ScriptHelper {
    @JvmStatic
    fun listAllTopLevelClassNames(): List<String> {
        val cl = Thread.currentThread().contextClassLoader
        return ClassPath.from(cl).topLevelClasses.map { it.name }
    }
}
