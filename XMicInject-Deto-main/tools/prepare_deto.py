#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
APP = ROOT / "app"
BUILD = APP / "build.gradle.kts"
MANIFEST = APP / "src/main/AndroidManifest.xml"

if not BUILD.exists():
    raise SystemExit(f"Missing {BUILD}")
if not MANIFEST.exists():
    raise SystemExit(f"Missing {MANIFEST}")

# The Kotlin sources intentionally stay in com.xmicinject so the upstream
# module entry point and package-local references remain stable. Only the
# installed applicationId becomes Deto-specific.
s = BUILD.read_text(encoding="utf-8")
new, n = re.subn(
    r'(applicationId\s*=\s*)"[^"]+"',
    r'\1"ae.deto.xmicinject"',
    s,
    count=1
)
if n != 1:
    raise SystemExit("Could not replace applicationId in upstream app/build.gradle.kts")
BUILD.write_text(new, encoding="utf-8")

# Make LSPosed/app UI identify the module as Deto without relying on a guessed
# resource filename.
m = MANIFEST.read_text(encoding="utf-8")
app_match = re.search(r'<application\b[^>]*>', m, re.S)
if not app_match:
    raise SystemExit("Could not find <application> in AndroidManifest.xml")
tag = app_match.group(0)
if 'android:label=' in tag:
    tag2 = re.sub(r'android:label\s*=\s*"[^"]*"', 'android:label="Deto"', tag, count=1)
else:
    tag2 = tag[:-1] + '\n        android:label="Deto">'
m = m[:app_match.start()] + tag2 + m[app_match.end():]
MANIFEST.write_text(m, encoding="utf-8")

print("Prepared upstream as XMicInject-Deto")
print("applicationId = ae.deto.xmicinject")
print("application label = Deto")
