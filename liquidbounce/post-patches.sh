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

echo "post-patches: done ($PKG_ROOT)"
