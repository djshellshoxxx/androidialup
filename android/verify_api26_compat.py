#!/usr/bin/env python3
"""Fail when core Android-bound Java uses library APIs unavailable on API 26.

The core modules are plain JVM projects, so javac/Gradle can compile calls to
newer java.* APIs even though the packaged Android app has minSdk 26. Keep the
check source-based and dependency-free so CI catches accidental regressions
before device testing.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
MODULES = ("core-modem", "core-protocol", "core-session", "core-network")
FORBIDDEN = {
    "List.of(": "java.util.List.of requires newer Android library support",
    "List.copyOf(": "java.util.List.copyOf requires newer Android library support",
    "Set.of(": "java.util.Set.of requires newer Android library support",
    "Map.of(": "java.util.Map.of requires newer Android library support",
    ".strip(": "String.strip requires API 33 on Android",
    ".stripLeading(": "String.stripLeading requires API 33 on Android",
    ".stripTrailing(": "String.stripTrailing requires API 33 on Android",
    ".writeBytes(": "ByteArrayOutputStream.writeBytes requires API 33 on Android",
}

violations = []
for module in MODULES:
    source_root = ROOT / module / "src" / "main" / "java"
    if not source_root.exists():
        continue
    for path in sorted(source_root.rglob("*.java")):
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for token, reason in FORBIDDEN.items():
                if token in line:
                    violations.append((path.relative_to(ROOT), lineno, token, reason, line.strip()))

if violations:
    print("API 26 compatibility violations found in Android-bound core Java:")
    for path, lineno, token, reason, line in violations:
        print(f"{path}:{lineno}: {token} - {reason}\n    {line}")
    sys.exit(1)

print("API 26 compatibility source check passed")
