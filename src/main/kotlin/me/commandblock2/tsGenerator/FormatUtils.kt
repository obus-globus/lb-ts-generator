package me.commandblock2.tsGenerator

import kotlin.reflect.KClass

fun KClass<*>.binaryName(): String {
    val fullName = this.java.name
    return fullName.substring(fullName.lastIndexOf('.') + 1)
}

fun String.commentIfInvalid(): String {
    return if (this.contains('-')) "// $this // ; invalid because of -" else this
}