#!/usr/bin/env bash
#
# ============================================================================
# SUPERSEDED — legacy standalone patch set (P-1/P-2 only)
#
# The live post-regen patch series (P-01'/P-02' plus T-1..T-10, TSDoc
# injection, etc.) is `tools/regen/post-patches.sh` in the consuming repo
# (obus-globus/lb-script-api-types). The canonical ts-defgen's T-1 pass now
# emits SAM classes (e.g. PolyglotScript$RegisterScript) as callable types
# directly, so on current-generator output the P-1 regexes below do not match
# and this script only partially applies (or aborts). It is kept ONLY for the
# standalone regen-enhanced.yml workflow in this repo.
# ============================================================================
#
# Apply post-regen "enhancement" patches to a regen output tree.
#
# These patches transform the raw output of the ts-generator into a form
# usable from script-author code. They are documented in
# liquidbounce-helper/docs/47-types-regeneration.md as P-1 and P-2.
#
# Usage:
#   patches/apply-enhancements.sh <regen-output-dir>
#
# where <regen-output-dir> is the directory containing
# @ccbluex/liquidbounce-script-api/{ambient,types,augmentations,...}.
#
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <regen-output-dir>" >&2
  exit 2
fi

ROOT="$1"
PKG="$ROOT/@ccbluex/liquidbounce-script-api"

if [[ ! -d "$PKG" ]]; then
  echo "FAIL: $PKG not found" >&2
  exit 1
fi

AMBIENT="$PKG/ambient/ambient.d.ts"
POLYGLOT="$PKG/types/net/ccbluex/liquidbounce/script/PolyglotScript.d.ts"

[[ -f "$AMBIENT" ]] || { echo "FAIL: missing $AMBIENT" >&2; exit 1; }
[[ -f "$POLYGLOT" ]] || { echo "FAIL: missing $POLYGLOT" >&2; exit 1; }

# ---------------------------------------------------------------------------
# P-1: registerScript callable type
# ---------------------------------------------------------------------------
# Replace the non-callable PolyglotScript$RegisterScript_ binding with a
# direct function-type declaration so `registerScript({...})` typechecks.
python3 - "$AMBIENT" <<'PY'
import re, sys, pathlib
p = pathlib.Path(sys.argv[1])
src = p.read_text()

new_import = (
    'import { PolyglotScript as PolyglotScript_ } '
    'from "../types/net/ccbluex/liquidbounce/script/PolyglotScript";'
)
src2 = re.sub(
    r'import\s*\{\s*PolyglotScript\$RegisterScript\s+as\s+PolyglotScript\$RegisterScript_\s*\}'
    r'\s*from\s*"[^"]*";',
    new_import,
    src,
    count=1,
)

new_decl = (
    'export const registerScript: '
    '(scriptObject: { name: string; version: string; authors: string[] }) '
    '=> PolyglotScript_;'
)
src3 = re.sub(
    r'export\s+const\s+registerScript\s*:\s*PolyglotScript\$RegisterScript_\s*;',
    new_decl,
    src2,
    count=1,
)

if src3 == src:
    # Maybe already patched — verify.
    if 'registerScript: (scriptObject:' not in src:
        sys.stderr.write("P-1: no changes applied AND not already patched. Aborting.\n")
        sys.exit(1)
    sys.stderr.write("P-1: already patched (no-op)\n")
else:
    p.write_text(src3)
    sys.stderr.write("P-1: applied to %s\n" % p)
PY

# ---------------------------------------------------------------------------
# P-2: registerModule callback type (ScriptModule, not ClientModule)
# ---------------------------------------------------------------------------
python3 - "$POLYGLOT" <<'PY'
import re, sys, pathlib
p = pathlib.Path(sys.argv[1])
src = p.read_text()

# Ensure ScriptModule import is present.
if 'ScriptModule' not in src.split('\n', 30)[0:30].__str__():
    pass  # cheap negative check below covers all cases
if "import type { ScriptModule }" not in src and "import { ScriptModule" not in src:
    # Insert after the last top-of-file import statement.
    lines = src.splitlines(keepends=True)
    last_import = 0
    for i, line in enumerate(lines[:60]):
        if line.startswith('import '):
            last_import = i
    lines.insert(
        last_import + 1,
        "import type { ScriptModule } from './bindings/features/ScriptModule.d.ts'\n",
    )
    src = ''.join(lines)

new_sig = (
    'registerModule(moduleObject: { name: string; category: string; '
    '[key: string]: unknown }, callback: (mod: ScriptModule) => void): void;'
)
src2 = re.sub(
    r'registerModule\(\s*moduleObject\s*:\s*\{\s*\[key\s*:\s*string\]\s*:\s*Object\s*\}\s*,'
    r'\s*callback\s*:\s*\(\s*param0\s*:\s*ClientModule\s*\)\s*=>\s*void\s*\)\s*:\s*void\s*;',
    new_sig,
    src,
    count=1,
)

if src2 == src:
    if 'callback: (mod: ScriptModule)' not in src:
        sys.stderr.write("P-2: registerModule signature not found and not already patched. Aborting.\n")
        sys.exit(1)
    sys.stderr.write("P-2: already patched (no-op)\n")
else:
    p.write_text(src2)
    sys.stderr.write("P-2: applied to %s\n" % p)
PY

echo "OK: enhancement patches applied to $PKG"
