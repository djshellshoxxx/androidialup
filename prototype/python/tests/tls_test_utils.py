from __future__ import annotations

from pathlib import Path
import subprocess


def generate_localhost_certificate(directory: str | Path, *, prefix: str = "relay") -> tuple[str, str]:
    root = Path(directory)
    cert = root / f"{prefix}-cert.pem"
    key = root / f"{prefix}-key.pem"
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-sha256",
            "-nodes",
            "-days",
            "1",
            "-keyout",
            str(key),
            "-out",
            str(cert),
            "-subj",
            "/CN=localhost",
            "-addext",
            "subjectAltName=DNS:localhost",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return str(cert), str(key)
