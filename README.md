# lb-ts-generator

Fork of [commandblock2/ts-generator](https://github.com/commandblock2/ts-generator) (itself a fork of [ntrrgc/ts-generator](https://github.com/ntrrgc/ts-generator)) used to regenerate TypeScript definitions for the LiquidBounce script API. It is embedded as the `generator/` git submodule of
[obus-globus/lb-script-api-types](https://github.com/obus-globus/lb-script-api-types) — the canonical typings + regen pipeline that publishes `@wunk/lb-script-api-types`. (The older `liquidbounce-helper` monorepo used it too, before the typings were split out.)

The generator walks Kotlin reflection metadata at runtime, producing one `.d.ts` per top-level class.

## Why this fork exists

The living value of this fork is **type quality**: a set of changes to the
Kotlin generator (and the polyglot `ts-defgen.js` that drives it) that produce
markedly better `.d.ts` output than vanilla `commandblock2/ts-generator`. See
**[ENHANCEMENTS.md](./ENHANCEMENTS.md)** for the full inventory; in brief:

- **E-01..E-05** (Kotlin generator) — per-class `.d.ts` layout; `Unit`/`Void` →
  `void`; `IntRange` collapse; deterministic member/supertype ordering; correct
  handling of Kotlin singleton `object`s that implement `Iterable`/`Collection`/`Map`.
- **E-06..E-08** (polyglot `ts-defgen`) — LB event auto-detection →
  `ScriptModule.on()` overloads; global-binding auto-detection → `ambient.d.ts`
  exports; an `augmentations/` barrel.
- **P-01/P-02** (post-regen patches) — make `registerScript({...})` callable and
  type the `registerModule` callback as `ScriptModule`.
- the **T-1..T-10** series — SAM / `Function`-typed callable detection,
  reserved-word parameter renames, `kotlin.Any?` → `unknown`, KDoc → TSDoc, etc.

### Legacy: the JDK-25 caller-sensitive workaround (superseded)

> **This is no longer used.** It's documented here because the fork still carries
> the `ScriptHelper.kt` + `fabric.mod.json` machinery, but the current pipeline
> doesn't invoke it — see below.

LiquidBounce's `ts-defgen.js` runs inside the GraalVM polyglot guest inside a
running Minecraft client. Starting with **JDK 25** several methods became
`@CallerSensitive` (`new URLClassLoader(URL[])`, `Thread.getContextClassLoader()`,
`Class.forName(...)`). GraalVM Truffle's host interop uses a restricted
`MethodHandles.Lookup`, which fails the CS check with:

```
IllegalAccessException: Attempt to lookup caller-sensitive method using restricted lookup object
```

(`--add-opens` does **not** fix this — the check is on the *caller class*, not on
module accessibility.) The original workaround packaged the generator as a Fabric
mod so its classes loaded under Knot, where the CS check passes:

1. **`ScriptHelper.kt`** — a Kotlin `object` with `@JvmStatic`
   `listAllTopLevelClassNames()` wrapping Guava's
   `ClassPath.from(Thread.currentThread().contextClassLoader)`.
2. **`fabric.mod.json`** in `src/main/resources/` — the shadow jar is itself a
   valid Fabric mod (`id: ts_generator`); dropped into `run/mods/`, the Knot
   classloader makes `me.commandblock2.tsGenerator.*` reachable from `Java.type(...)`.
3. **Guava as `compileOnly`** — provided at runtime by LB / MC; not shadowed.

**Why it's superseded:** LiquidBounce fixed caller-sensitivity upstream in
[`b759cac57`](https://github.com/CCBlueX/LiquidBounce/commit/b759cac57) (PR #8437,
`MixinCallerSensitiveDetection`). The canonical pipeline's `ts-defgen.js` now
loads the generator jar via a plain `createClassLoaderFromJar(...)` and enumerates
classes with Guava `ClassPath` directly — **no mod dropped in `run/mods/`, no
`ScriptHelper`**. The jar is still built as a valid Fabric mod (harmless), but the
enhancements above, not the mod packaging, are the reason to use this fork today.

## Build

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew shadowJar
```

(Gradle 8.10 / Kotlin 2.0 can't read Java 25 class files yet, so the
build itself needs JDK ≤21. The resulting jar runs fine on JDK 25.)

The shadow jar lands at `build/libs/ts-generator-1.1.4-all.jar` and is
loadable as a Fabric mod as-is.

## Usage from polyglot (`ts-defgen.js`)

The current pipeline loads the generator classes from the jar via a custom
classloader and enumerates the classpath with Guava directly (no mod, no
`ScriptHelper` — see the legacy note above):

```js
const loader = createClassLoaderFromJar(path + "/ts-generator.jar");
const NPMPackageGenerator = loadClassFromJar(loader, 'me.commandblock2.tsGenerator.NPMPackageGenerator');
const TypeScriptGenerator = loadClassFromJar(loader, 'me.ntrrgc.tsGenerator.TypeScriptGenerator');
// class enumeration:
const ClassPath = Java.type('com.google.common.reflect.ClassPath');
const names = ClassPath.from(Thread.currentThread().getContextClassLoader());
```

> **Legacy:** the mod-packaged path instead used
> `Java.type('me.commandblock2.tsGenerator.ScriptHelper').listAllTopLevelClassNames()`.
> Retired once LB shipped the upstream caller-sensitive fix.

## License

Same as upstream: Apache-2.0 / GPL-3.0 (see `LICENSE-*.md`).

## GitHub Actions

Three workflows live in `.github/workflows/`:

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| `build.yml` | push / PR / manual | Build the Fabric-mod shadow jar on JDK 21 and upload it as the `ts-generator-mod` artifact. |
| `regen-raw.yml` | manual only | Build the mod, clone LiquidBounce at the chosen ref, run the client headlessly under `xvfb`, and upload the raw generator output as a single zstd-compressed tarball. **No enhancement patches applied.** |
| `regen-enhanced.yml` | manual only | Same as `regen-raw`, then runs `patches/apply-enhancements.sh` to apply P-1 (`registerScript` callable) and P-2 (`registerModule` callback typed `ScriptModule`) before packaging the artifact. |

> **Note:** the two regen workflows still run the **legacy mod/ScriptHelper
> flow** using this repo's stale copies (`liquidbounce/ts-defgen.js`,
> `patches/apply-enhancements.sh`) — kept only as a standalone CI fallback.
> The live pipeline (URLClassLoader jar loading, full T-series patch set) is
> `tools/regen/` in the consuming repo, obus-globus/lb-script-api-types.

The two regen workflows accept inputs:

- `lb_repo` — defaults to `CCBlueX/LiquidBounce`
- `lb_ref` — branch / tag / SHA (defaults to `nextgen`)
- `runclient_timeout` — minutes (default `75`)

A regen run produces ~57k `.d.ts` files (~270 MB uncompressed, ~50 MB
compressed). Expect roughly 60–90 minutes of wall-clock time on a
GitHub-hosted `ubuntu-latest` runner — most of it inside Kotlin reflection.

## Enhancement patches

The "enhancement" patches applied by `regen-enhanced.yml` are inventoried in
[ENHANCEMENTS.md](./ENHANCEMENTS.md) (the canonical regen pipeline that consumes
them lives in [obus-globus/lb-script-api-types](https://github.com/obus-globus/lb-script-api-types);
the legacy `liquidbounce-helper/docs/47-types-regeneration.md` predates the split):

- **P-1** — rewrites `ambient/ambient.d.ts` so `registerScript({...})` is
  callable (the generator emits a `PolyglotScript$RegisterScript_` binding
  that has no call signature).
- **P-2** — rewrites the `registerModule` signature in
  `types/net/ccbluex/liquidbounce/script/PolyglotScript.d.ts` so the
  callback parameter is typed `ScriptModule` (which exposes `.on`,
  `.settings`, etc.) instead of `ClientModule`.

The script is idempotent — running it twice is a no-op.

See **[ENHANCEMENTS.md](./ENHANCEMENTS.md)** for the full inventory of all
13 type-quality changes we ship vs. vanilla CCBlueX output, with rationale
and step-by-step regeneration mechanics.
