# lb-ts-generator

Fork of [commandblock2/ts-generator](https://github.com/commandblock2/ts-generator) (itself a fork of [ntrrgc/ts-generator](https://github.com/ntrrgc/ts-generator)) used to regenerate TypeScript definitions for the LiquidBounce script API in
[clawdbot-silly-waddle/liquidbounce-helper](https://github.com/clawdbot-silly-waddle/liquidbounce-helper).

The generator walks Kotlin reflection metadata at runtime, producing one `.d.ts` per top-level class.

## Why this fork exists

LiquidBounce's `ts-defgen.js` runs inside the GraalVM polyglot guest inside
a running Minecraft client. Starting with **JDK 25** several methods became
`@CallerSensitive` (or had their CS enforcement tightened):

- `new URLClassLoader(URL[])`
- `Thread.getContextClassLoader()`
- `Class.forName(...)`

GraalVM Truffle's host interop uses a restricted `MethodHandles.Lookup`,
which fails the CS check with:

```
IllegalAccessException: Attempt to lookup caller-sensitive method using restricted lookup object
```

`--add-opens` does **not** fix this — the CS check is on the *caller class*,
not on module accessibility.

This fork's contribution on top of upstream:

1. **`ScriptHelper.kt`** — a Kotlin `object` with `@JvmStatic`
   `listAllTopLevelClassNames()` wrapping Guava's
   `ClassPath.from(Thread.currentThread().contextClassLoader)`. Because
   the call originates from a Kotlin class loaded by Knot (not from
   polyglot guest code), the CS check passes.
2. **`fabric.mod.json`** in `src/main/resources/` — when shadowed, the
   output jar is itself a valid Fabric mod (`id: ts_generator`). Drop it
   into `run/mods/` and the Knot classloader makes
   `me.commandblock2.tsGenerator.*` reachable from `Java.type(...)` in
   the polyglot script.
3. **Guava as `compileOnly`** — provided at runtime by LB / MC; not
   shadowed.

## Build

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew shadowJar
```

(Gradle 8.10 / Kotlin 2.0 can't read Java 25 class files yet, so the
build itself needs JDK ≤21. The resulting jar runs fine on JDK 25.)

The shadow jar lands at `build/libs/ts-generator-1.1.4-all.jar` and is
loadable as a Fabric mod as-is.

## Usage from polyglot (`ts-defgen.js`)

```js
const ScriptHelper = Java.type('me.commandblock2.tsGenerator.ScriptHelper');
const names = ScriptHelper.listAllTopLevelClassNames(); // List<String>
```

```js
const NPMPackageGenerator   = Java.type('me.commandblock2.tsGenerator.NPMPackageGenerator');
const TypeScriptGenerator   = Java.type('me.ntrrgc.tsGenerator.TypeScriptGenerator');
```

## License

Same as upstream: Apache-2.0 / GPL-3.0 (see `LICENSE-*.md`).
