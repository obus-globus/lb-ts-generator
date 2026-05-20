/*
 * Copyright 2024-2025 commandblock2
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package me.commandblock2.tsGenerator

import me.ntrrgc.tsGenerator.TypeScriptGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

// extensions for TypeScriptGenerator
fun TypeScriptGenerator.generateNPMPackage(packageName: String): NPMPackageGenerator {
    return NPMPackageGenerator(this, packageName)
}

// The generator class

class NPMPackageGenerator(
    val typeScriptGenerator: TypeScriptGenerator,
    val packageName: String,
    val version: String = "1.0.0",
    extraFiles: String = "",
    extraTypesVersion: String = "",
    otherExtras: String = "",
    // New parameter to directly inject the typesVersions JSON content
    customTypesVersionsJson: String? = null
) {
    val typesFolder = "types"
    val packageJson = """
{
    "name": "$packageName",
    "version": "$version",
    "files": [
        "$typesFolder/**/*.d.ts"
        ${if (extraFiles.isNotEmpty()) ",\n$extraFiles" else ""}
    ],
    "typesVersions": ${
        // If custom JSON is provided, use it directly (assume it's valid JSON for typesVersions)
        customTypesVersionsJson
            ?: // Otherwise, generate the default typesVersions block
            """{
                    "*": {
                        "*": [
                            "./$typesFolder/*"
                            ${if (extraTypesVersion.isNotEmpty()) ",\n$extraTypesVersion" else ""}
                        ]
                    }
                }"""
    }
    ${if (otherExtras.isNotEmpty()) ",\n$otherExtras" else ""}
}
    """.trimIndent()

    val tsConfig = """
        {
            "compilerOptions": {
                "target": "es2018",
                "module": "commonjs",
                "declaration": true,
                "declarationMap": true,
                "baseUrl": ".",
                "paths": {
                    "*": ["$typesFolder/*"]
                },
                "strict": true,
                "moduleResolution": "node",
                "esModuleInterop": true,
                "skipLibCheck": false,
                "forceConsistentCasingInFileNames": true
            },
            "include": [
                "$typesFolder/**/*.d.ts"
            ]
        }
    """.trimIndent()

    fun writePackageTo(path: Path) {
        val packageFolder = path.resolve(packageName)
        val typesPath = packageFolder.resolve(typesFolder)

        Files.createDirectories(packageFolder)
        Files.createDirectories(typesPath)

        // package.json
        packageFolder.resolve("package.json").writeText(packageJson)

        // tsconfig.json
        packageFolder.resolve("tsconfig.json").writeText(tsConfig)

        typeScriptGenerator.definitionsAsModules.forEach { (path, content) ->
            val definition = typesPath.resolve(path)
            Files.createDirectories(definition.parent)
            definition.writeText(content)
        }
    }
}