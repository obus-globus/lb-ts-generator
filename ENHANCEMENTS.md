# Enhancements over the vanilla LiquidBounce script-api types

This document inventories every change this repo (and its consumer,
[`liquidbounce-helper`](https://github.com/clawdbot-silly-waddle/liquidbounce-helper))
makes to the TypeScript declarations published by CCBlueX as
`@ccbluex/liquidbounce-script-api`.

> **"Vanilla"** in the comparison column means the output you'd get from
> the original [`commandblock2/ts-generator`](https://github.com/commandblock2/ts-generator)
> (CCBlueX's own pipeline) running against an unmodified
> [`ts-defgen.js`](https://github.com/CCBlueX/LiquidBounce/blob/nextgen/scripts/ts-defgen.js)
> on the corresponding LiquidBounce build.

## Quick legend

- 🟢 **Automatic** — happens unattended on every regen, no human action required.
- 🟡 **Manual** — applied by `patches/apply-enhancements.sh` after the regen; idempotent but explicit.
- 🛠 **Infrastructure** — not a type-shape change, but enables the regen to run at all.

---

## Index

| # | Name | Layer | Trigger |
|---|------|-------|---------|
| E-01 | Per-class `.d.ts` package layout | ts-generator (Kotlin) | 🟢 Automatic |
| E-02 | `Unit` / `Void` / `Void.TYPE` → `void` | ts-generator (Kotlin) | 🟢 Automatic |
| E-03 | `IntRange` collapse | ts-generator (Kotlin) | 🟢 Automatic |
| E-04 | Deterministic ordering of members & supertypes | ts-generator (Kotlin) | 🟢 Automatic |
| E-05 | Kotlin singleton `object` ⊃ `Iterable`/`Collection`/`Map` handling | ts-generator (Kotlin) | 🟢 Automatic |
| E-06 | LB event auto-detection → 121-overload `ScriptModule.on()` augmentation | ts-defgen (polyglot) | 🟢 Automatic |
| E-07 | Global-binding auto-detection → `ambient.d.ts` exports | ts-defgen (polyglot) | 🟢 Automatic |
| E-08 | `augmentations/index.d.ts` barrel file | ts-defgen (polyglot) | 🟢 Automatic |
| P-01 | `registerScript` callable type | post-regen patch | 🟡 Manual |
| P-02 | `registerModule` → `ScriptModule` callback | post-regen patch | 🟡 Manual |
| I-01 | Fabric-mod-wrapped shadow jar (`fabric.mod.json` baked in) | mod packaging | 🛠 Infrastructure |
| I-02 | `ScriptHelper.kt` + `Java.type(...)` rewrite of `ts-defgen.js` | mod + polyglot | 🛠 Infrastructure |

---

## Generator-side enhancements (`src/main/kotlin/.../TypeScriptGenerator.kt`)

### E-01 — Per-class `.d.ts` package layout

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | Upstream [`ntrrgc/ts-generator`](https://github.com/ntrrgc/ts-generator) emits **one giant `.d.ts`** containing every reflected class. |
| **Our output** | One `.d.ts` per top-level class, mirroring the JVM package directory layout (`@ccbluex/liquidbounce-script-api/net/ccbluex/liquidbounce/...`). 57k+ files. |
| **Why** | TypeScript can resolve imports lazily; only types you actually `import` get parsed. With one monolithic file every script would parse 270 MB of types on every `tsc` run. |
| **Origin** | Inherited from [`commandblock2/ts-generator`](https://github.com/commandblock2/ts-generator)'s `NPMPackageGenerator.kt`. Used by both upstream LB and us; documented here because it's *not* what the original ntrrgc generator does. |

**Steps performed (automatic, every regen):**
1. `TypeScriptGenerator` produces an in-memory list of `(KClass, .d.ts text)` pairs.
2. `NPMPackageGenerator.write(generated, packageName, version, …)` walks the list and, for each entry, computes the on-disk path from `kClass.qualifiedName.replace('.', '/')`.
3. A `package.json` + `index.d.ts` are emitted at the package root with `extraFiles` pointing at `augmentations/**` and `ambient/ambient.d.ts` so TS picks them up.

---

### E-02 — `Unit` / `Void` / `Void.TYPE` → `void`

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | Kotlin `fun foo(): Unit` and Java `void foo()` both end up emitted as **`Unit`** (an unresolved Kotlin reflection type). |
| **Our output** | Both emit `void`. Applied uniformly: in return-type position *and* anywhere `Unit`/`Void`/`Void.TYPE` shows up nested in generics. |
| **Why** | `Unit` is not an importable TS type. Every void-returning function (≈ every action / event handler in MC + LB) would otherwise type as `any`, removing the compiler's check that handlers don't accidentally return values. |
| **Implementation** | `builtinTypes` map in `TypeScriptGenerator.kt`: `Unit::class → "void"`, `Void::class → "void"`. Plus a return-type path that catches `Void.TYPE.kotlin.java.name` (which otherwise leaks the primitive name). |

**Steps performed (automatic):**
1. During reflection of each `KFunction`, the return `KType` is resolved.
2. The resolved classifier is looked up in `builtinTypes`; `Unit`/`Void` map to `"void"`.
3. Same check is applied recursively when formatting nested type arguments (so e.g. `Function0<Unit>` becomes `() => void`, not `() => Unit`).

---

### E-03 — `IntRange` collapse

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | Reflects `IntRange` as a Kotlin class with ~10 internal members (`first`, `last`, `step`, `isEmpty()`, `iterator()`, `_first`, …) — many marked `internal` but visible to reflection. |
| **Our output** | `IntRange` is type-aliased to `{ start: number; endInclusive: number; step: number }`. |
| **Why** | LB uses `IntRange` extensively as a setting type. Without the collapse, every range-typed setting requires importing the internal Kotlin class and is annotated with unusable internals. |
| **Implementation** | `builtinTypes[IntRange::class] = "{ start: number; endInclusive: number; step: number }"`. |

---

### E-04 — Deterministic ordering

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | Member order in each `.d.ts` follows JVM reflection order, which is **non-deterministic** between runs and JVM builds. |
| **Our output** | Stable, lexicographic-with-tiebreaker ordering at 8 sites: properties, methods, constructors, supertypes, interface supertypes, dependent-type imports, … |
| **Why** | (a) Diff-based regression detection in CI (`regen-types-check.sh`) — without stable ordering every regen produces a giant diff. (b) Smaller PR review surface when LB legitimately adds members. |
| **Implementation** | Eight `sortedBy`/`sortedWith(compareBy({ it.name }, { it.toString() }))` calls in `TypeScriptGenerator.kt`. |

---

### E-05 — Kotlin singleton `object` implementing `Iterable`/`Collection`/`Map`

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | A Kotlin `object Foo : List<Bar>` would emit as `interface Foo extends List<Bar>` and the singleton's *own* members (`fun specialThing()`) would be lost — Kotlin reflection routes the class through the collection path. |
| **Our output** | The collection interfaces are **stripped** from the singleton declaration. The object's own members stay visible. Plain (non-singleton) Collection subtypes are unaffected. |
| **Why** | LB has several singleton registries that happen to implement `Iterable`/`Map`. Authors need to call the registry's domain-specific methods, not iterate it. |
| **Implementation** | In `TypeScriptGenerator.kt`, the singleton-detection branch (`kClass.objectInstance != null`) routes through a code path that skips collection-supertype handling. |

---

## Polyglot-side enhancements (`liquidbounce/ts-defgen.js`)

### E-06 — Event auto-detection (121-overload `ScriptModule.on()`)

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | `ScriptModule.on(eventName: string, handler: (param0: Object) => void): void;` — a single generic signature. No literal-name narrowing. |
| **Our output** | 121 overloads (one per `@Tag`-annotated LB Event subclass) + 2 synthetic ones for `"enable"`/`"disable"`. Misspelled event names fail to typecheck; handler param is narrowed to the matching event type. |
| **Why** | Catches `mod.on('attck', ...)` at compile time; gives autocomplete on the handler argument's fields (`e.entity`, `e.target`, …). |

**Steps performed (automatic, every regen):**
1. `ts-defgen.js` line 111 reads LB's `EVENT_NAME_TO_CLASS` map via `ReflectionUtil.getDeclaredField(EventKt, "EVENT_NAME_TO_CLASS")`.
2. LB populates this map at startup from every `@Tag(name = "…")`-annotated `Event` subclass — fully runtime-discovered.
3. For each `(name, kClass)` entry, emit:
   - an `import type` line referencing the event class's `.d.ts`
   - an `on(eventName: "<name>", handler: (...) => void): void;` overload
4. Result is written to `augmentations/ScriptModule.augmentation.d.ts`.
5. TypeScript declaration merging picks the augmentation up via the `augmentations/index.d.ts` barrel.

**Adding/removing/renaming an event upstream requires zero manual action here** — next regen catches it automatically.

---

### E-07 — Global-binding auto-detection (`ambient.d.ts`)

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | Hand-maintained ambient declarations; tend to drift when LB adds new bindings. |
| **Our output** | `ambient.d.ts` is generated by enumerating `Object.entries(globalThis)` inside the polyglot guest, so every binding LB poked into the script scope shows up. |
| **Why** | LB occasionally adds new top-level bindings (`ScriptAsyncUtil`, `ScriptUnsafeThread`, …). Manual lists go stale. |

**Steps performed (automatic):**
1. `ts-defgen.js` line 40: `const globalEntries = Object.entries(globalThis);` — at this point in the polyglot script, `globalThis` *is* the GraalVM binding scope.
2. For each entry, classify:
   - If value is a `java.lang.Class` → emit `export const X: typeof X_;` (LB exposed the class itself, like `Vec3i`).
   - Otherwise it's an instance → emit `export const X: ClassOfInstance_;`.
3. Imports for each referenced class are emitted at the top of `ambient.d.ts`.

> **Not covered:** GraalVM intrinsics (`Java.type`, `Polyglot`, `load`, …). They're built into the engine, not exposed on `globalThis` as enumerable properties. See [docs/upstream-type-issues/05-graalvm-globals-in-ambient.md](https://github.com/clawdbot-silly-waddle/liquidbounce-helper/blob/main/docs/upstream-type-issues/05-graalvm-globals-in-ambient.md) — open as a future P-03.

---

### E-08 — `augmentations/index.d.ts` barrel

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | `ambient.d.ts` does `import "../augmentations/index.d.ts"` but the file is never written → silent broken import → all 121 event overloads orphaned. |
| **Our output** | One-liner barrel: `export * from './ScriptModule.augmentation';`. |
| **Why** | Without this file, E-06's augmentation is dead code: TS resolves the import to nothing and `mod.on('flag', …)` falls back to the generic string signature. |
| **Implementation** | `ts-defgen.js` writes both `ScriptModule.augmentation.d.ts` and the matching `index.d.ts`. |

---

## Post-regen patches (manual, applied by `patches/apply-enhancements.sh`)

### P-01 — `registerScript` callable type

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | `import { PolyglotScript$RegisterScript as PolyglotScript$RegisterScript_ } from "...";`  `export const registerScript: PolyglotScript$RegisterScript_;` — a class binding with only an `.apply()` method, no call signature. |
| **Our output** | `export const registerScript: (scriptObject: { name: string; version: string; authors: string[] }) => PolyglotScript_;` |
| **Why** | Every script's first line is `let script = registerScript({ name, version, authors })`. Without P-01, TS raises **TS2349 "This expression is not callable"** on line 1 of every single script. |
| **Manual reason** | This is a runtime-truth-vs-static-type mismatch. The generator faithfully reflects what's in JVM-land (a single-abstract-method class); it can't know the polyglot bridge exposes it as a callable. Encoding this in the generator would require LB-specific heuristics. |

**Steps performed:**
1. `apply-enhancements.sh` opens `ambient/ambient.d.ts`.
2. Regex 1: rewrite the import.
   ```
   /import\s*\{\s*PolyglotScript\$RegisterScript\s+as\s+PolyglotScript\$RegisterScript_\s*\}\s*from\s*"[^"]*";/
     → import { PolyglotScript as PolyglotScript_ } from "../types/net/ccbluex/liquidbounce/script/PolyglotScript";
   ```
3. Regex 2: rewrite the declaration.
   ```
   /export\s+const\s+registerScript\s*:\s*PolyglotScript\$RegisterScript_\s*;/
     → export const registerScript: (scriptObject: { name: string; version: string; authors: string[] }) => PolyglotScript_;
   ```
4. Idempotent: if neither regex matched and `registerScript: (scriptObject:` is already present, log "already patched (no-op)" and exit clean. If it's neither pre-patch nor post-patch shape → abort with non-zero exit.
5. `regen-enhanced.yml` then runs a sanity-grep for `registerScript: (scriptObject:` and fails the workflow if missing.

**Regression gate (in `liquidbounce-helper`):** `check.sh` gate #18b (`register-script-callable.test.ts`).

---

### P-02 — `registerModule` callback type (`ScriptModule`, not `ClientModule`)

| Aspect | Description |
|--------|-------------|
| **Vanilla output** | `registerModule(moduleObject: { [key: string]: Object }, callback: (param0: ClientModule) => void): void;` — `ClientModule` is the base class; has no `.on()`, `.settings()`, `.name`, etc. |
| **Our output** | Imports `ScriptModule`; signature becomes `registerModule(moduleObject: { name: string; category: string; [key: string]: unknown }, callback: (mod: ScriptModule) => void): void;` |
| **Why** | Every `mod.on(...)` and `mod.settings(...)` inside a `registerModule` block otherwise raises **TS2339 "Property 'on' does not exist on type 'ClientModule'"**. The whole 121-overload augmentation from E-06 is also wasted — it's keyed on `ScriptModule`, not `ClientModule`. |
| **Manual reason** | At reflection time, the static type of the callback parameter is `ClientModule`. The runtime object is a `ScriptModule` subclass that LB constructs internally. The generator can't know that without LB-specific knowledge. |

**Steps performed:**
1. Open `types/net/ccbluex/liquidbounce/script/PolyglotScript.d.ts`.
2. If no `import { ScriptModule` or `import type { ScriptModule }` is present in the first 60 lines, insert `import type { ScriptModule } from './bindings/features/ScriptModule.d.ts'` after the last existing import.
3. Regex over the signature:
   ```
   /registerModule\(\s*moduleObject\s*:\s*\{\s*\[key\s*:\s*string\]\s*:\s*Object\s*\}\s*,
     \s*callback\s*:\s*\(\s*param0\s*:\s*ClientModule\s*\)\s*=>\s*void\s*\)\s*:\s*void\s*;/
     → registerModule(moduleObject: { name: string; category: string; [key: string]: unknown }, callback: (mod: ScriptModule) => void): void;
   ```
4. Idempotent: same "already patched" detection as P-01.
5. Sanity-grep for `callback: (mod: ScriptModule)`.

**Regression gates (in `liquidbounce-helper`):** `check.sh` gate #2 (`good.mjs` typechecks) and gate #11 (template-project end-to-end).

---

## Infrastructure (enables regen to run at all under JDK 25 / GraalVM)

### I-01 — Fabric-mod-wrapped shadow jar

| Aspect | Description |
|--------|-------------|
| **Vanilla** | The ts-generator jar gets dropped into `run/LiquidBounce/scripts/ts-generator.jar` and loaded via `new URLClassLoader(...)` inside `ts-defgen.js`. |
| **Ours** | Same jar, but its `src/main/resources/fabric.mod.json` makes it a *valid Fabric mod* (`id: ts_generator`). We additionally drop a copy into `run/mods/`. The Knot classloader then loads its classes onto the runtime classpath at startup. |
| **Why** | JDK 25 tightened `@CallerSensitive` enforcement on `new URLClassLoader(...)` (and `Thread.getContextClassLoader()`, `Class.forName(...)`, …). GraalVM Truffle's host interop uses a restricted `MethodHandles.Lookup` that fails the CS check with `IllegalAccessException`. `--add-opens` does **not** help — the CS check is on the *caller class*, not on module accessibility. **Upstream CCBlueX hits the same bug** on every `generate-definitions.yml` run since v0.38.0; their workflow has `\|\| exit 0` so it silently uploads no artifact. |

**Steps performed (automatic, every build):**
1. `./gradlew shadowJar` packages all Kotlin sources + `src/main/resources/fabric.mod.json` into one fat jar.
2. The resulting `ts-generator-1.1.4-all.jar` is loadable as a Fabric mod with no further wrapping.

---

### I-02 — `ScriptHelper.kt` static + `Java.type(...)` rewrite

| Aspect | Description |
|--------|-------------|
| **Vanilla `ts-defgen.js`** | Uses `new URLClassLoader([jar.toURI().toURL()], Thread.currentThread().getContextClassLoader())` and then `loader.loadClass(name)` to reach the generator classes. Both calls are `@CallerSensitive` on JDK 25 → `IllegalAccessException` in polyglot. |
| **Ours** | (a) Removed the URLClassLoader entirely — generator classes are already on the classpath via I-01, so `Java.type("me.commandblock2.tsGenerator.NPMPackageGenerator")` reaches them directly. (b) Replaced `Thread.currentThread().getContextClassLoader()` (used by `findAllClassInfos()` for classpath enumeration) with a call to a new Kotlin static, `ScriptHelper.listAllTopLevelClassNames()`. |
| **Why ScriptHelper exists** | `Thread.getContextClassLoader()` is `@CallerSensitive`; calling it from polyglot guest code fails. Calling it from a Kotlin `@JvmStatic` method works because the CS check sees a real JVM class as the caller, not GraalVM's restricted lookup. |
| **Why a wrapper around Guava's `ClassPath`** | Same reason — `ClassPath.from(classLoader).getTopLevelClasses()` internally walks the classloader's resources; we centralise both that walk and the CS-sensitive `Thread.getContextClassLoader()` into one helper. |

**Steps performed (automatic, every regen):**
1. The mod (built by I-01) is loaded by Knot at MC startup; `me.commandblock2.tsGenerator.*` is on the runtime classpath.
2. `ts-defgen.js` lines 92, 113, etc. call `Java.type("me.commandblock2.tsGenerator.ScriptHelper").listAllTopLevelClassNames()` → returns `List<String>` of every top-level class name visible from the Knot classloader.
3. Each returned name is wrapped in a `{ getName: () => name }` shim (so the downstream `.getName()` chain still works) and fed into the generator's class set.

---

## Comparison summary table

```
ID    Vanilla (CCBlueX)                              Ours                                                Mode
────  ────────────────────────────────────────────   ──────────────────────────────────────────────────  ─────────────
E-01  One monolithic .d.ts                           Per-class .d.ts in package layout (~57k files)      🟢 Auto
E-02  Kotlin Unit → "Unit" (unresolved)              Unit / Void / Void.TYPE → "void"                    🟢 Auto
E-03  IntRange → full Kotlin class                   IntRange → { start; endInclusive; step }            🟢 Auto
E-04  Reflection-order (non-deterministic)           sortedBy/sortedWith at 8 sites                      🟢 Auto
E-05  Singleton object<Collection> = collection      Collection interfaces stripped on singletons        🟢 Auto
E-06  on(name: string, h: (Object) => void)          121 literal-narrowed overloads + 2 meta             🟢 Auto
E-07  Hand-listed ambient bindings                   globalThis enumeration → ambient.d.ts               🟢 Auto
E-08  Orphaned augmentations/                        index.d.ts barrel written                           🟢 Auto
P-01  registerScript: non-callable bridge class      Direct function type                                🟡 Manual
P-02  registerModule cb: ClientModule                Cb: ScriptModule + typed descriptor                 🟡 Manual
I-01  ts-generator.jar loaded via URLClassLoader     Loaded by Knot via fabric.mod.json                  🛠 Infra
I-02  ts-defgen uses CS methods in polyglot          Java.type + ScriptHelper static                     🛠 Infra
```

---

## Known gaps (not yet implemented)

These are documented in `liquidbounce-helper/docs/upstream-type-issues/`:

| Issue | Description | Difficulty |
|-------|-------------|------------|
| #2 | `Setting.<factory>({...})` arg typed as opaque `Value` | High (needs schema per factory) |
| #4 | Reserved-word identifier rename (`void`/`null`/`var`/`function` as field names parse as `any`) | Medium (sed on generator output) |
| #5 | GraalVM intrinsics (`Java.type`, `Polyglot`, `load`) missing from ambient | Easy (P-03 candidate — hand-curated prelude) |
| #6 | `PolyglotScript.on(...)` literal-narrowed overloads | Easy (same loop as E-06, different declaration) |
| #8 | DSL receiver lambdas `T.() -> Unit` → `Function1<T, void>` (loses `this`) | High; deferred |

---

## How to run the regen end-to-end

| Goal | How |
|------|-----|
| Just build the mod jar (used as input for the regen workflows or for local LB launches) | `./gradlew shadowJar` — drops `build/libs/ts-generator-1.1.4-all.jar`. |
| Build + regen + raw output | Trigger **Regen LB types (raw)** workflow. Artifact: `script-api-types-raw-<sha>`. |
| Build + regen + applied P-01/P-02 | Trigger **Regen LB types (with enhancements)** workflow. Artifact: `script-api-types-enhanced-<sha>`. |
| Apply patches to an already-extracted regen tree | `patches/apply-enhancements.sh <dir-containing-@ccbluex>` |
