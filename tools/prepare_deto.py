#!/usr/bin/env python3
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

text = build.read_text(encoding="utf-8")
text, n = re.subn(
    r'(applicationId\s*=\s*)"[^"]+"',
    r'\1"ae.deto.xmicinject"',
    text,
    count=1,
)
if n != 1:
    raise SystemExit("Could not set applicationId")

build.write_text(text, encoding="utf-8")

m = manifest.read_text(encoding="utf-8")
match = re.search(r"<application\b[^>]*>", m, re.S)
if not match:
    raise SystemExit("Could not find <application>")

tag = match.group(0)
if "android:label=" in tag:
    tag = re.sub(
        r'android:label\s*=\s*"[^"]*"',
        'android:label="Deto"',
        tag,
        count=1,
    )
else:
    tag = tag[:-1] + '\n        android:label="Deto">'

manifest.write_text(
    m[:match.start()] + tag + m[match.end():],
    encoding="utf-8",
)

print("Deto build preparation complete")
