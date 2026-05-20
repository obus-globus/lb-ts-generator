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
    "new|null|return|super|switch|this|throw|true|try|typeof|var|void|while|"
    "with|implements|interface|let|package|private|protected|public|static|"
    "yield|await"
)

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

for path in types_root.rglob("*.d.ts"):
    total_files += 1
    text = path.read_text()
    new_text, n = PARAM_NAME.subn(r"\1\2_\3", text)
    if n:
        path.write_text(new_text)
        changed_files += 1
        total_subs += n

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

echo "post-patches: done ($PKG_ROOT)"
