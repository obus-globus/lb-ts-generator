#!/usr/bin/env bash
#
# Post-regen refinement patches applied to tools/regen-output/.
#
# Why: T-1 (in ts-defgen.js) auto-detects classes implementing
# java.util.function.Function and emits the export as `Type_['apply']` which
# IS callable but exposes the raw Java parameter shape (typically
# Map<String, Object> → `{ [key: string]: Object }`). For a small number of
# script-API entry points the parameter is a runtime contract with named
# keys; we narrow the parameter type here so users get autocomplete on
# those keys.
#
# This is intentionally a tiny, explicit, idempotent overlay — NOT a general
# patcher. Each refinement is scoped to one named binding.
#
# Idempotency: every transform is a no-op when its target line already
# matches the refined form. Re-running this script is safe.
#
# Usage:
#   tools/regen/post-patches.sh                # patch tools/regen-output/
#   tools/regen/post-patches.sh <pkg-root>     # patch arbitrary tree
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG_ROOT="${1:-$REPO_ROOT/tools/regen-output/@ccbluex/liquidbounce-script-api}"

if [[ ! -d "$PKG_ROOT" ]]; then
  echo "FAIL: package root not found: $PKG_ROOT" >&2
  exit 1
fi

AMBIENT="$PKG_ROOT/ambient/ambient.d.ts"
if [[ ! -f "$AMBIENT" ]]; then
  echo "FAIL: ambient.d.ts not found: $AMBIENT" >&2
  exit 1
fi

# P-01-prime: refine the `registerScript` parameter shape from the raw
# Map<String, Object> projection (auto-emitted by T-1) to the runtime
# contract { name; version; authors }. This change is two edits:
#   1. import PolyglotScript directly (the return type), drop the
#      PolyglotScript$RegisterScript import alias.
#   2. spell out the callable signature with the narrow parameter shape.
python3 - "$AMBIENT" <<'PY'
import io, re, sys
from pathlib import Path

path = Path(sys.argv[1])
src = path.read_text()
orig = src

# (1) Replace the RegisterScript import with the PolyglotScript import.
import_old = (
    'import { PolyglotScript$RegisterScript as PolyglotScript$RegisterScript_ } '
    'from "../types/net/ccbluex/liquidbounce/script/PolyglotScript$RegisterScript";'
)
import_new = (
    'import { PolyglotScript as PolyglotScript_ } '
    'from "../types/net/ccbluex/liquidbounce/script/PolyglotScript";'
)
already_have_polyglot_import = import_new in src

if import_old in src:
    if already_have_polyglot_import:
        # PolyglotScript already imported elsewhere — just drop the
        # RegisterScript line, preserving the surrounding newline.
        src = src.replace(import_old + "\n", "")
    else:
        src = src.replace(import_old, import_new)

# (2) Refine the registerScript export. Match both the T-1 indexed-access form
#     and the legacy raw form so this script can promote either to the
#     narrow shape.
export_new = (
    'export const registerScript: (scriptObject: { name: string; version: string; '
    'authors: string[] }) => PolyglotScript_;'
)
patterns = [
    # T-1 indexed-access form
    r'export const registerScript: PolyglotScript\$RegisterScript_\["apply"\];',
    # Legacy raw form (before T-1 landed)
    r'export const registerScript: PolyglotScript\$RegisterScript_;',
]
applied = export_new in src
if not applied:
    for pat in patterns:
        new, n = re.subn(pat, export_new.replace("\\", r"\\"), src)
        if n:
            src = new
            applied = True
            break

if src == orig:
    print(f"post-patches: no-op on {path.name} (already refined or pattern missing)")
else:
    path.write_text(src)
    print(f"post-patches: refined {path.name} (P-01-prime: registerScript param shape)")

# Sanity: the resulting ambient must contain the narrow form.
if export_new not in path.read_text():
    print(f"FAIL: post-patch did not produce the expected registerScript line in {path}", file=sys.stderr)
    sys.exit(2)
PY

# P-02-prime: refine PolyglotScript.registerModule().
#
# The Kotlin signature is `registerModule(Map<String, Any>, Consumer<ClientModule>)`
# but at runtime the callback receives a ScriptModule (a polyglot proxy that
# wraps ClientModule with script-friendly methods like `bind(...)`,
# `setting(...)`, etc.), see PolyglotScript.kt line ~219:
#
#     val module = ScriptModule(this, moduleObject)
#     callback.accept(module)
#
# There is no static signal for this — the type promotion must be expressed
# as an overlay. We also narrow the descriptor parameter to the runtime
# contract { name; category; …extras } so authors get autocomplete on the
# two required keys.
python3 - "$PKG_ROOT/types/net/ccbluex/liquidbounce/script/PolyglotScript.d.ts" <<'PY'
import re, sys
from pathlib import Path

path = Path(sys.argv[1])
if not path.exists():
    print(f"post-patches: skipping P-02 — {path} not found", file=sys.stderr)
    sys.exit(0)

src = path.read_text()
orig = src
script_module_import = (
    "import type { ScriptModule } from './bindings/features/ScriptModule.d.ts'"
)
register_module_new = (
    "    registerModule(moduleObject: { name: string; category: string; "
    "[key: string]: unknown }, callback: (mod: ScriptModule) => void): void;"
)

# (1) Ensure the ScriptModule import exists. Insert right after the
#     ClientModule import to keep the block visually grouped.
if script_module_import not in src:
    client_module_import = re.search(
        r"^import type \{ ClientModule \} from '[^']+';?$",
        src, re.MULTILINE,
    )
    if client_module_import:
        end = client_module_import.end()
        src = src[:end] + "\n" + script_module_import + src[end:]
    else:
        print(
            "post-patches: WARNING P-02 — no ClientModule import found in "
            "PolyglotScript.d.ts; ScriptModule import not added",
            file=sys.stderr,
        )

# (2) Rewrite the registerModule signature. Match the raw generator output
#     (Map<String, Object> param + ClientModule callback) and the already-
#     refined form (idempotent no-op).
already_refined = register_module_new in src
if not already_refined:
    # Tolerate small whitespace variation in the raw signature.
    raw_pat = re.compile(
        r"^[ \t]*registerModule\(\s*moduleObject:\s*\{\s*\[key:\s*string\]:\s*Object\s*\},"
        r"\s*callback:\s*\(\s*param0:\s*ClientModule\s*\)\s*=>\s*void\s*\):\s*void;",
        re.MULTILINE,
    )
    new, n = raw_pat.subn(register_module_new, src)
    if n:
        src = new
    else:
        # Fall back: any registerModule(...) one-liner.
        loose = re.compile(r"^[ \t]*registerModule\([^\n]*\):\s*void;", re.MULTILINE)
        new, n = loose.subn(register_module_new, src)
        if n:
            src = new

if src == orig:
    print(f"post-patches: P-02 no-op on {path.name} (already refined or pattern missing)")
else:
    path.write_text(src)
    print(f"post-patches: refined {path.name} (P-02-prime: registerModule signature)")

# Sanity.
if register_module_new not in path.read_text():
    print(
        f"FAIL: P-02 did not produce the expected registerModule signature in {path}",
        file=sys.stderr,
    )
    sys.exit(2)
PY

# T-3: rename TS reserved-word parameter names across all generated
# .d.ts files under types/. These appear because ntrrgc/ts-generator copies
# parameter names verbatim from the Java/Kotlin bytecode, where synthetic or
# anonymous params can be emitted as identifiers that happen to collide with
# TS reserved words (e.g. `null` for inner-class outer references, `in`/`var`
# /`function`/`yield`/`await` for fastutil/Guava methods). Each occurrence
# triggers TS1390/TS1359/TS1138 parse errors and pollutes `tsc` output even
# when the user's own code is clean.
#
# Strategy: ONLY transform identifiers that are unambiguously in parameter
# position — preceded by `(` or `, ` and followed by `?: ` or `: `. Append a
# single `_` to disambiguate from the keyword. Idempotent because the
# renamed form (e.g. `null_`) no longer matches the pattern.
python3 - "$PKG_ROOT/types" <<'PY'
import re, sys, time
from pathlib import Path

types_root = Path(sys.argv[1])
if not types_root.is_dir():
    print(f"post-patches: skipping T-3 — {types_root} not a directory", file=sys.stderr)
    sys.exit(0)

# Reserved words that cannot be used as identifiers in TS strict mode.
RESERVED = (
    "break|case|catch|class|const|continue|debugger|default|delete|do|else|"
    "enum|export|extends|false|finally|for|function|if|import|in|instanceof|"
    "new|null|return|super|switch|throw|true|try|typeof|var|void|while|"
    "with|implements|interface|let|package|private|protected|public|static|"
    "yield|await"
)
# Note: `this` is intentionally excluded — TypeScript supports a typed
# `this` parameter in function signatures (`(this: T, ...) => R`) which
# T-7 relies on, and no real Java/Kotlin parameter is ever named `this`
# at the bytecode level, so excluding it is also a no-op for raw output.

# Match identifier in parameter position. Three groups:
#   1: the leading char(s) we want to preserve verbatim (`(`, `,`, `<` or
#      whitespace after them).
#   2: the reserved word itself.
#   3: the trailing context (`?:` or `:` followed by whitespace).
#
# We rely on the param-list-like syntax that always brackets the identifier
# between an opening bracket / comma and a colon. This is precise enough to
# avoid hitting return type positions (which are preceded by `)` not `(`)
# and union/intersection types (which use `|` and `&`, never `:`).
PARAM_NAME = re.compile(
    r"([\(,]\s*(?:readonly\s+)?(?:\.{3})?)"
    rf"({RESERVED})"
    r"(\??:\s)"
)

total_files = 0
total_subs = 0
changed_files = 0
start = time.time()

# Skip identifier-rename inside /** ... */ block comments (TSDoc / JSDoc).
# These are user-facing prose where keyword-suffixing the wrong word would
# turn correct example code into nonsense (e.g. `default:` in @example).
COMMENT_BLOCK = re.compile(r"/\*\*(?:[^*]|\*(?!/))*\*/")

for path in types_root.rglob("*.d.ts"):
    total_files += 1
    text = path.read_text()
    # Split into [code, comment, code, comment, ...]; even indices are code.
    parts = COMMENT_BLOCK.split(text)
    comments = COMMENT_BLOCK.findall(text)
    file_subs = 0
    for i, segment in enumerate(parts):
        new_segment, n = PARAM_NAME.subn(r"\1\2_\3", segment)
        if n:
            parts[i] = new_segment
            file_subs += n
    if file_subs:
        # Re-interleave: parts[0] + comments[0] + parts[1] + comments[1] + …
        rebuilt = []
        for i, p in enumerate(parts):
            rebuilt.append(p)
            if i < len(comments):
                rebuilt.append(comments[i])
        path.write_text("".join(rebuilt))
        changed_files += 1
        total_subs += file_subs

elapsed = time.time() - start
print(
    f"post-patches: T-3 renamed reserved-word params — "
    f"{total_subs} substitutions across {changed_files}/{total_files} files "
    f"({elapsed:.1f}s)"
)
PY

# T-4: declare GraalVM JS intrinsics inside the ambient `declare global { }`
# block. These are exposed by the GraalVM Truffle host to every script as
# top-level bindings but are NOT enumerable via Object.entries(globalThis)
# (they live as non-enumerable host-provided properties on the global), so
# the ts-defgen.js auto-detect pass never sees them. We inject the type
# declarations here so authors get autocomplete on `Java.type(...)`,
# `Polyglot.import(...)`, `print(...)`, and friends.
#
# Marker comment `// T-4: GraalVM intrinsics begin` keeps the patch idempotent.
python3 - "$AMBIENT" <<'PY'
import re, sys
from pathlib import Path

path = Path(sys.argv[1])
src = path.read_text()
marker_begin = "    // T-4: GraalVM intrinsics begin"
marker_end = "    // T-4: GraalVM intrinsics end"
if marker_begin in src:
    print(f"post-patches: T-4 no-op on {path.name} (marker present)")
    sys.exit(0)

block = f'''{marker_begin}
    // Truffle/GraalVM host-provided globals — exposed to every polyglot
    // script but invisible to `Object.entries(globalThis)`. See:
    //   https://www.graalvm.org/jdk25/reference-manual/js/JavaInteroperability/
    //   https://www.graalvm.org/jdk25/reference-manual/polyglot-programming/
    interface JavaIntrinsic {{
        /** Resolve a Java class by FQN. Returns a "type" handle: callable as
         *  a constructor and indexable for static members. */
        type<T = any>(className: string): T;
        /** Convert a Java array (or Iterable) to a JS array. */
        from<T = unknown>(javaArray: any): T[];
        /** Convert a JS iterable to a Java array of the given element type. */
        to(jsArray: ArrayLike<unknown>, javaType?: string | any): any;
        /** Extend one or more Java classes / interfaces. */
        extend(...types: any[]): any;
        /** Call a superclass method on a Java-extended object. */
        super(obj: any): any;
        /** Run a callback while holding the intrinsic monitor of `lock`. */
        synchronized<T>(fn: () => T, lock: any): T;
        isJavaObject(obj: unknown): boolean;
        isJavaFunction(obj: unknown): boolean;
        isScriptObject(obj: unknown): boolean;
        isScriptFunction(obj: unknown): boolean;
        isType(obj: unknown): boolean;
        typeName(type: any): string;
        asJSONCompatible(obj: any): any;
    }}
    const Java: JavaIntrinsic;
    /** GraalVM polyglot bindings — shared key/value space across languages. */
    interface PolyglotIntrinsic {{
        import<T = unknown>(name: string): T;
        export<T>(name: string, value: T): void;
        eval<T = unknown>(language: string, source: string): T;
        evalFile<T = unknown>(language: string, source: string): T;
    }}
    const Polyglot: PolyglotIntrinsic;
    /** Print to stdout with a trailing newline. */
    function print(...args: unknown[]): void;
    /** Print to stderr with a trailing newline. */
    function printErr(...args: unknown[]): void;
    /** Evaluate JS source from a string, file path, or URL. */
    function load(source: string | {{ name: string; script: string }}): unknown;
    /** Like `load`, but evaluates in a fresh global scope. */
    function loadWithNewGlobal(source: string | {{ name: string; script: string }}, ...args: unknown[]): unknown;
    /** GraalVM runtime metadata. */
    const Graal: {{
        readonly language: string;
        readonly versionECMAScript: string;
        readonly versionGraalVM: string;
        readonly isGraalRuntime: boolean;
    }};
    /** Worker-thread API (only when js.worker is enabled). */
    const Workers: any;
{marker_end}
'''

# Insert the block right after the `declare global {` line. Match exactly to
# avoid touching anything unintended.
new_src, n = re.subn(
    r'(^declare global \{\n)',
    lambda m: m.group(1) + block,
    src, count=1, flags=re.MULTILINE,
)
if n == 0:
    print(
        f"post-patches: WARNING T-4 — `declare global {{` not found in {path.name}; "
        "GraalVM intrinsics not injected",
        file=sys.stderr,
    )
    sys.exit(0)

path.write_text(new_src)
print(f"post-patches: T-4 injected GraalVM intrinsics into {path.name}")
PY

# T-5: PolyglotScript.on() literal-string overloads. The Kotlin signature
# is `on(eventName: String, handler: Runnable)` (which the generator emits
# as `on(eventName: string, handler: () => void): void`). Only three
# event names are ever dispatched: "load", "enable", "disable" — see
# PolyglotScript.kt callGlobalEvent() callers. Adding string-literal
# overloads gives autocomplete on those names and rejects typos. The
# generic string fallback is preserved as the last overload so power-users
# who depend on dynamic event names aren't broken.
python3 - "$PKG_ROOT/types/net/ccbluex/liquidbounce/script/PolyglotScript.d.ts" <<'PY'
import re, sys
from pathlib import Path

path = Path(sys.argv[1])
if not path.exists():
    print(f"post-patches: skipping T-5 — {path} not found", file=sys.stderr)
    sys.exit(0)

src = path.read_text()
overload_block_marker = '    on(eventName: "load" | "enable" | "disable", handler: () => void): void;'
if overload_block_marker in src:
    print(f"post-patches: T-5 no-op on {path.name} (overload already present)")
    sys.exit(0)

old_line = re.compile(
    r'^[ \t]*on\(eventName: string, handler: \(\) => void\): void;[ \t]*$',
    re.MULTILINE,
)
replacement = (
    f'{overload_block_marker}\n'
    f'    /** @deprecated Only "load" | "enable" | "disable" are dispatched '
    f'by PolyglotScript — see `callGlobalEvent` in PolyglotScript.kt. Use the '
    f'literal-overload above for editor autocomplete. */\n'
    f'    on(eventName: string, handler: () => void): void;'
)
new_src, n = old_line.subn(replacement, src, count=1)
if n == 0:
    print(
        f"post-patches: WARNING T-5 — generic on() signature not found in {path.name}",
        file=sys.stderr,
    )
    sys.exit(0)
path.write_text(new_src)
print(f"post-patches: T-5 added on() literal overloads to {path.name}")
PY

# T-6: ScriptSetting factory option-object signatures. Each ScriptSetting
# method takes a single org.graalvm.polyglot.Value parameter and reads
# named members off it (name, default, range, suffix, choices, canBeNone).
# The runtime contract is enforced by Kotlin code (ScriptSetting.kt), not
# by the static type. Without these overlays, autocomplete shows nothing
# inside the option object and authors have to read source code to know
# what keys to pass. We replace each `value: Value` parameter with the
# named-key object shape required at runtime.
python3 - "$PKG_ROOT/types/net/ccbluex/liquidbounce/script/bindings/features/ScriptSetting.d.ts" <<'PY'
import re, sys
from pathlib import Path

path = Path(sys.argv[1])
if not path.exists():
    print(f"post-patches: skipping T-6 — {path} not found", file=sys.stderr)
    sys.exit(0)

src = path.read_text()
orig = src

# (method_name, raw signature suffix, replacement signature with refined
# parameter shape). Return types match the regenerated form exactly so the
# diff stays minimal and the rewrite is reversible.
SIGS = [
    (
        "boolean",
        "boolean(option: { name: string; default: boolean }): Value<boolean>;",
    ),
    (
        "float",
        "float(option: { name: string; default: number; range: [number, number]; "
        "suffix?: string }): RangedValue<number>;",
    ),
    (
        "floatRange",
        "floatRange(option: { name: string; default: [number, number]; "
        "range: [number, number]; suffix?: string }): "
        "RangedValue<ClosedFloatingPointRange<number>>;",
    ),
    (
        "int",
        "int(option: { name: string; default: number; range: [number, number]; "
        "suffix?: string }): RangedValue<number>;",
    ),
    (
        "intRange",
        "intRange(option: { name: string; default: [number, number]; "
        "range: [number, number]; suffix?: string }): "
        "RangedValue<{ start: number; endInclusive: number; step: number }>;",
    ),
    (
        "key",
        "key(option: { name: string; default: string }): Value<InputConstants$Key>;",
    ),
    (
        "text",
        "text(option: { name: string; default: string }): Value<string>;",
    ),
    (
        "textArray",
        "textArray(option: { name: string; default: string[] }): Value<string[]>;",
    ),
    (
        "choose",
        "choose<C extends readonly string[]>(option: { name: string; choices: C; "
        "default: C[number] }): ChoiceListValue<Tagged>;",
    ),
    (
        "multiChoose",
        "multiChoose<C extends readonly string[]>(option: { name: string; "
        "choices: C; default?: ReadonlyArray<C[number]>; canBeNone?: boolean }): "
        "MultiChoiceListValue<Tagged>;",
    ),
]

substitutions = 0
for method, refined in SIGS:
    indented = "    " + refined
    if indented in src:
        continue  # idempotent
    # Match: `    <method>(value: Value): <return>;` — generated form.
    raw_pat = re.compile(
        rf"^[ \t]*{re.escape(method)}\(value: Value\):[^\n]+;[ \t]*$",
        re.MULTILINE,
    )
    new, n = raw_pat.subn(indented, src, count=1)
    if n:
        src = new
        substitutions += 1

if src == orig:
    print(f"post-patches: T-6 no-op on {path.name} (already refined or pattern missing)")
else:
    path.write_text(src)
    print(
        f"post-patches: T-6 refined {substitutions}/{len(SIGS)} ScriptSetting "
        f"factories in {path.name}"
    )
PY

# -----------------------------------------------------------------------------
# T-7 — DSL receiver lambda: ValueGroup.curve
# -----------------------------------------------------------------------------
# Kotlin signature:
#   inline fun curve(name: String, block: CurveValue.Builder.() -> Unit): CurveValue
# Generated TS:
#   curve(name: string, block: Function1<CurveValue$Builder, void>): CurveValue;
# Function1 is an interface with an `invoke` method — TS callers can't pass an
# arrow function. The receiver (`this` inside block) is lost entirely.
#
# Refined to a TS function type with `this`-parameter binding so callers can
# write `group.curve("x", function () { this.tension = 0.5 })`.

VG_FILE="$PKG_ROOT/types/net/ccbluex/liquidbounce/config/types/group/ValueGroup.d.ts"
if [ -f "$VG_FILE" ]; then
python3 - "$VG_FILE" <<'PY'
import sys, re
from pathlib import Path

path = Path(sys.argv[1])
src = path.read_text()

raw = "    curve(name: string, block: Function1<CurveValue$Builder, void>): CurveValue;"
refined = "    curve(name: string, block: (this: CurveValue$Builder) => void): CurveValue;"

if refined in src:
    print(f"post-patches: T-7 no-op on {path.name} (already refined)")
elif raw in src:
    src = src.replace(raw, refined, 1)
    path.write_text(src)
    print(f"post-patches: T-7 refined ValueGroup.curve in {path.name}")
else:
    print(f"post-patches: T-7 skip — raw curve signature not found in {path.name}")
PY
fi

# -----------------------------------------------------------------------------
# T-Doc (Phase A POC) — inject TSDoc comments on a curated set of high-traffic
# script-facing methods so authors get docstring tooltips in VS Code without
# having to read Kotlin source. This is a manual seed; the eventual fix
# (Issue #11) is a kdoc-extractor → ts-generator pipeline that automates the
# whole surface. For now, hard-coding the highest-traffic ~5 endpoints proves
# the rendering pathway works and gives immediate UX lift.
#
# Source of each docstring is recorded in a `Source:` line so future
# automation can verify/replace them.

python3 - "$PKG_ROOT" <<'PY'
import sys, re
from pathlib import Path

root = Path(sys.argv[1])

# Each entry: (relative file path under root,
#              marker — the exact line we anchor before,
#              docstring text — already wrapped in /** ... */ with leading indent)
# Idempotency: skip if the docstring's first line is already present
# directly above the marker.

DOCS = [
    # PolyglotScript.registerScript on the ambient global (post-patches P-01-prime
    # rewrites this from the Java-bridge class form).
    (
        "ambient/ambient.d.ts",
        "    export const registerScript: (scriptObject: { name: string; version: string; authors: string[] }) => PolyglotScript_;",
        """    /**
     * Registers a new script with LiquidBounce. **Must be called exactly once**
     * at the top level of every script — the return value is your script
     * handle (used to register modules, listen for lifecycle events, etc.).
     *
     * @param scriptObject Identity metadata for this script.
     * @param scriptObject.name Display name. Shown in the script manager.
     * @param scriptObject.version Semver-ish version string.
     * @param scriptObject.authors One or more author names.
     * @returns The script handle for chaining further registrations.
     *
     * @example
     * ```ts
     * const script = registerScript({
     *     name: "MyScript",
     *     version: "1.0.0",
     *     authors: ["me"],
     * });
     *
     * script.on("load", () => print("loaded"));
     * ```
     *
     * Source: `PolyglotScript.kt` — `RegisterScript.apply`, KDoc.
     */""",
    ),
    # PolyglotScript.registerModule
    (
        "types/net/ccbluex/liquidbounce/script/PolyglotScript.d.ts",
        "    registerModule(moduleObject: { name: string; category: string; [key: string]: unknown }, callback: (mod: ScriptModule) => void): void;",
        """    /**
     * Registers a new module backed by this script. The callback receives a
     * fully-constructed {@link ScriptModule} which you configure (settings,
     * event handlers, render logic) before returning. The module is added
     * to LiquidBounce's module manager as soon as your script is enabled.
     *
     * @param moduleObject Metadata describing the module.
     * @param moduleObject.name Display name shown in the ClickGUI.
     * @param moduleObject.category One of `"Combat" | "Movement" | "Player" | "Render" | "World" | "Misc" | "Fun" | "Exploit" | "Client"`.
     * @param callback Configurator invoked once at registration. Use it to
     *                 declare settings (`module.setting.boolean(...)`),
     *                 bind events (`module.on(...)`), and define behaviour.
     *
     * @example
     * ```ts
     * script.registerModule({ name: "MyModule", category: "Misc" }, (mod) => {
     *     const enabled = mod.setting.boolean({ name: "loud", default: false });
     *     mod.on("enable", () => print("on"));
     * });
     * ```
     *
     * Source: `PolyglotScript.kt:213` — KDoc on `fun registerModule`.
     */""",
    ),
    # PolyglotScript.on — literal-event overload (the narrow one only; the
    # @deprecated fallback already has a comment so we leave it alone).
    (
        "types/net/ccbluex/liquidbounce/script/PolyglotScript.d.ts",
        '    on(eventName: "load" | "enable" | "disable", handler: () => void): void;',
        """    /**
     * Binds a handler to one of this script's lifecycle events.
     *
     * @param eventName Lifecycle event to listen for:
     *   - `"load"` — fired once when LiquidBounce finishes loading this
     *               script source (before any module registration takes
     *               effect). Use it for one-time global setup.
     *   - `"enable"` — fired every time the user enables this script in
     *                 the script manager (after `load`, and after each
     *                 hot-reload).
     *   - `"disable"` — fired when the user disables / unloads this
     *                  script. Use it to release resources, unbind
     *                  external listeners, etc.
     * @param handler Zero-argument callback. None of the three lifecycle
     *                events carry a payload.
     *
     * @example
     * ```ts
     * script.on("enable", () => print("hello"));
     * script.on("disable", () => print("bye"));
     * ```
     *
     * Source: `PolyglotScript.kt:282` — KDoc on `fun on`; payload shape
     * confirmed by `callGlobalEvent` call sites.
     */""",
    ),
    # ValueGroup.curve (T-7 receiver-lambda form)
    (
        "types/net/ccbluex/liquidbounce/config/types/group/ValueGroup.d.ts",
        "    curve(name: string, block: (this: CurveValue$Builder) => void): CurveValue;",
        """    /**
     * Declares a {@link CurveValue} setting using the Kotlin-DSL-style
     * builder. Inside the `block`, `this` is bound to {@link CurveValue$Builder}
     * so you can configure curve points fluently. **Use a `function` (not an
     * arrow), or the `this` binding will be lost.**
     *
     * @param name Display name of the setting.
     * @param block Builder configurator. `this` is the curve builder.
     * @returns The curve setting, which you can use to read interpolated
     *          values at runtime.
     *
     * @example
     * ```ts
     * const easing = group.curve("Speed Curve", function () {
     *     this.tension = 0.5;
     *     // configure points...
     * });
     * ```
     *
     * Source: `ValueGroup.kt:502` — inline DSL builder. (Method has no
     * KDoc in upstream; this docstring is authored locally.)
     */""",
    ),
    # ScriptSetting.boolean — pick one factory as POC; the others can be
    # auto-generated later by the kdoc-extractor pipeline.
    (
        "types/net/ccbluex/liquidbounce/script/bindings/features/ScriptSetting.d.ts",
        "    boolean(option: { name: string; default: boolean }): Value<boolean>;",
        """    /**
     * Creates a boolean setting (rendered as a toggle / checkbox in the
     * ClickGUI). The value can be read via `.get()` at runtime.
     *
     * @param option.name Display name shown in the ClickGUI.
     * @param option.default Initial value if the user hasn't changed it.
     * @returns The setting handle. Call `.get()` to read the current value.
     *
     * @example
     * ```ts
     * const loud = mod.setting.boolean({ name: "Loud", default: false });
     * if (loud.get()) print("loud!");
     * ```
     *
     * Source: `ScriptSetting.kt:43` — `fun boolean(value: PolyglotValue)`,
     * reads the `name` and `default` members. Class-level KDoc states
     * "Object used by the script API to provide an idiomatic way of
     * creating module values."
     */""",
    ),
]

injected = 0
skipped_present = 0
skipped_missing = 0

for rel, marker, doc in DOCS:
    path = root / rel
    if not path.exists():
        skipped_missing += 1
        continue
    src = path.read_text()
    # Build the block to insert (doc + newline + marker).
    block = doc + "\n" + marker
    if block in src:
        skipped_present += 1
        continue
    if marker not in src:
        skipped_missing += 1
        continue
    # Single replacement (file may legitimately contain the marker once).
    src = src.replace(marker, block, 1)
    path.write_text(src)
    injected += 1

print(
    f"post-patches: T-Doc injected {injected}/{len(DOCS)} TSDoc blocks "
    f"({skipped_present} already present, {skipped_missing} missing target)"
)
PY

echo "post-patches: done ($PKG_ROOT)"
