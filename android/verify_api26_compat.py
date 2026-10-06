#!/usr/bin/env python3
"""Guard Android minSdk 26 Java-library compatibility.

The core modules compile as plain JVM code, so javac can accept java.* methods
that are absent on older Android releases. Supported java.util collection
factories are handled by Android core-library desugaring in both packaging
modules; APIs not provided by that desugaring remain forbidden here.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
MODULES = ("core-modem", "core-protocol", "core-session", "core-network")
FORBIDDEN = {
    ".strip(": "String.strip requires API 33 and is not in the configured desugared API set",
    ".stripLeading(": "String.stripLeading requires API 33 and is not in the configured desugared API set",
    ".stripTrailing(": "String.stripTrailing requires API 33 and is not in the configured desugared API set",
    ".writeBytes(": "ByteArrayOutputStream.writeBytes requires API 33 and is not in the configured desugared API set",
}

DESUGAR_MODULES = ("app", "platform-android")
DESUGAR_SWITCH = "coreLibraryDesugaringEnabled true"
DESUGAR_DEP = "coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.3'"

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

config_errors = []
for module in DESUGAR_MODULES:
    build_file = ROOT / module / "build.gradle"
    text = build_file.read_text(encoding="utf-8")
    if DESUGAR_SWITCH not in text:
        config_errors.append(f"{build_file.relative_to(ROOT)}: missing {DESUGAR_SWITCH!r}")
    if DESUGAR_DEP not in text:
        config_errors.append(f"{build_file.relative_to(ROOT)}: missing pinned desugar_jdk_libs dependency")

if violations or config_errors:
    if violations:
        print("Unsupported API 26 compatibility violations found in Android-bound core Java:")
        for path, lineno, token, reason, line in violations:
            print(f"{path}:{lineno}: {token} - {reason}\n    {line}")
    if config_errors:
        print("Core-library desugaring configuration errors:")
        for error in config_errors:
            print(error)
    sys.exit(1)

print("API 26 compatibility check passed: unsupported calls absent and core-library desugaring configured")
