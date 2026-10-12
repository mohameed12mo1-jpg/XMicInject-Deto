#!/usr/bin/env python3
"""Prepare the stable XMicInject v3 source for Wevo without changing audio logic."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
build = root / "app" / "build.gradle.kts"
manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"

if not build.exists():
    raise SystemExit("Missing " + str(build))
if not manifest.exists():
    raise SystemExit("Missing " + str(manifest))

# Keep Kotlin namespace com.xmicinject and all runtime/audio code unchanged.
# Give the installed LSPosed module a distinct Wevo application ID.
text = build.read_text(encoding="utf-8")
text, n = re.subn(
    r'(applicationId\s*=\s*)"[^"]+"',
    r'\1"ae.wevo.xmicinject"',
    text,
    count=1,
)
if n != 1:
    raise SystemExit("Could not set applicationId to ae.wevo.xmicinject")
build.write_text(text, encoding="utf-8")

# Set only the visible module label. The Xposed entry point stays unchanged.
xml = manifest.read_text(encoding="utf-8")
match = re.search(r"<application\b[^>]*>", xml, re.S)
if not match:
    raise SystemExit("Could not find <application> in AndroidManifest.xml")

tag = match.group(0)
if "android:label=" in tag:
    tag = re.sub(
        r'android:label\s*=\s*"[^"]*"',
        'android:label="Wevo"',
        tag,
        count=1,
    )
else:
    tag = tag[:-1] + '\n        android:label="Wevo">'
manifest.write_text(xml[:match.start()] + tag + xml[match.end():], encoding="utf-8")

# Assert the target package constant in the patched hook is Wevo.
hook = root / "app" / "src" / "main" / "java" / "com" / "xmicinject" / "XMicHook.kt"
if not hook.exists():
    raise SystemExit("Missing copied Wevo XMicHook.kt")
hook_text = hook.read_text(encoding="utf-8")
if 'private const val WEVO = "com.all2chat.voip"' not in hook_text:
    raise SystemExit("Hook target is not set to com.all2chat.voip")
if "ae.deto.app" in hook_text:
    raise SystemExit("Old Deto hook target remains in XMicHook.kt")

print("Prepared XMicInject-Wevo")
print("applicationId = ae.wevo.xmicinject")
print("application label = Wevo")
print("hook target = com.all2chat.voip")
