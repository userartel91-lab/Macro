#!/bin/sh
# Compile check for environments WITHOUT LWJGL / ASM jars (e.g. a Claude cloud session: Maven Central is blocked).
# Compiles everything with plain javac and prints only the errors NOT caused by the missing libraries.
# "OK: no errors besides missing LWJGL/ASM" means the code is fine apart from library symbols javac cannot see.
# Usage (from the DerpCraft folder):  sh tools/check_compile.sh
cd "$(dirname "$0")/.." || exit 1
find src/main/java -name '*.java' > /tmp/dc_srcs.txt
javac -nowarn -Xmaxerrs 100000 -encoding UTF-8 -d /tmp/dc_out @/tmp/dc_srcs.txt > /tmp/dc_err.txt 2>&1
python3 - <<'PY'
import re
txt = open('/tmp/dc_err.txt', encoding='utf-8', errors='replace').read()
blocks = re.split(r'\n(?=src/)', txt)
total = sum(1 for b in blocks if 'error:' in b)
bad = []
for b in blocks:
    if 'error:' not in b: continue
    if re.search(r'symbol:\s+(variable|method|class) (gl|GL|GLFW|glfw)', b): continue
    if 'org.lwjgl' in b or 'org.objectweb' in b or 'package Type' in b: continue
    if '/patch/' in b.split('\n')[0]: continue          # PatchRegistry uses ASM everywhere
    bad.append(b.strip())
print(f"javac errors total: {total} (expected ~1150-1200, all from missing LWJGL/ASM)")
if bad:
    print(f"REAL ERRORS: {len(bad)}\n")
    print('\n---\n'.join(bad[:40]))
else:
    print("OK: no errors besides missing LWJGL/ASM")
PY
