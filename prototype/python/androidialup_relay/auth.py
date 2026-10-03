"""Per-device credential challenge/response authentication for the ADUP relay.

Beta method ``device-credential-hmac-sha256-v1``:

    proof = HMAC-SHA256(key = device_secret, msg = transcript)

    transcript =
        b"ADUP-AUTH-v1"                      (12 ASCII bytes, no length prefix)
        || u16be(len(relay_id_utf8)) || relay_id_utf8
        || u16be(len(nonce))         || nonce          (nonce: 16..65535 bytes)
        || endpoint_id                                  (exactly 32 raw bytes)

All integers are big-endian, matching the ADUP wire encoding.  The relay_id is
the value the relay sent in HELLO_ACK, the nonce the value from AUTH_CHALLENGE,
and the endpoint_id the value the client sent in HELLO.  Binding relay_id means
a proof captured for one relay is useless against another; the per-session
random nonce makes every proof single-use.

Nothing in this module logs, prints, or embeds secrets or proofs in ``repr``.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
from collections.abc import Callable, Mapping
from typing import Protocol, runtime_checkable

from androidialup_protocol.messages import AuthChallenge

DEVICE_HMAC_SHA256_V1 = "device-credential-hmac-sha256-v1"
TRANSCRIPT_PREFIX = b"ADUP-AUTH-v1"
ENDPOINT_ID_LENGTH = 32
MIN_NONCE_LENGTH = 16
MAX_NONCE_LENGTH = 0xFFFF
DEFAULT_NONCE_LENGTH = 32
PROOF_LENGTH = hashlib.sha256().digest_size
AUTH_FAILED_REASON = "authentication failed"


def _validate_nonce(nonce: bytes) -> bytes:
    nonce = bytes(nonce)
    if not MIN_NONCE_LENGTH <= len(nonce) <= MAX_NONCE_LENGTH:
        raise ValueError(f"nonce must be {MIN_NONCE_LENGTH}..{MAX_NONCE_LENGTH} bytes")
    return nonce


def _validate_endpoint_id(endpoint_id: bytes) -> bytes:
    endpoint_id = bytes(endpoint_id)
    if len(endpoint_id) != ENDPOINT_ID_LENGTH:
        raise ValueError(f"endpoint_id must be exactly {ENDPOINT_ID_LENGTH} bytes")
    return endpoint_id


def _validate_relay_id(relay_id: str) -> bytes:
    raw = relay_id.encode("utf-8")
    if len(raw) > 0xFFFF:
        raise ValueError("relay_id exceeds 65535 UTF-8 bytes")
    return raw


def _validate_secret(device_secret: bytes) -> bytes:
    device_secret = bytes(device_secret)
    if not device_secret:
        raise ValueError("device_secret must not be empty")
    return device_secret


def build_transcript(nonce: bytes, endpoint_id: bytes, relay_id: str) -> bytes:
    """Return the exact bytes that are MACed; see the module docstring."""
    nonce = _validate_nonce(nonce)
    endpoint_id = _validate_endpoint_id(endpoint_id)
    relay_utf8 = _validate_relay_id(relay_id)
    return (
        TRANSCRIPT_PREFIX
        + len(relay_utf8).to_bytes(2, "big")
        + relay_utf8
        + len(nonce).to_bytes(2, "big")
        + nonce
        + endpoint_id
    )


def compute_proof(device_secret: bytes, nonce: bytes, endpoint_id: bytes, relay_id: str) -> bytes:
    """HMAC-SHA256 proof for ``DEVICE_HMAC_SHA256_V1``; 32 bytes."""
    key = _validate_secret(device_secret)
    return hmac.new(key, build_transcript(nonce, endpoint_id, relay_id), hashlib.sha256).digest()


@runtime_checkable
class DeviceCredentialStore(Protocol):
    def secret_for(self, endpoint_id: bytes) -> bytes | None:
        """Return the device secret for ``endpoint_id`` or ``None`` when unknown."""


class InMemoryDeviceCredentialStore:
    """Simple provisioning table: endpoint_id (32 bytes) -> device secret."""

    def __init__(self, credentials: Mapping[bytes, bytes] | None = None) -> None:
        self._secrets: dict[bytes, bytes] = {}
        for endpoint_id, secret in (credentials or {}).items():
            self.add(endpoint_id, secret)

    def add(self, endpoint_id: bytes, secret: bytes) -> None:
        self._secrets[_validate_endpoint_id(endpoint_id)] = _validate_secret(secret)

    def remove(self, endpoint_id: bytes) -> None:
        self._secrets.pop(bytes(endpoint_id), None)

    def secret_for(self, endpoint_id: bytes) -> bytes | None:
        return self._secrets.get(bytes(endpoint_id))

    def __len__(self) -> int:
        return len(self._secrets)

    def __repr__(self) -> str:
        return f"InMemoryDeviceCredentialStore(entries={len(self._secrets)})"


class ChallengeResponseAuthenticator:
    """Per-session relay-side verifier for ``DEVICE_HMAC_SHA256_V1``.

    ``begin`` issues one fresh random nonce and remembers the pending
    challenge; ``verify`` checks a proof in constant time and consumes the
    challenge whether or not it matched, so each nonce is usable exactly once.
    Unknown endpoints and bad proofs are indistinguishable to the client.
    """

    method = DEVICE_HMAC_SHA256_V1

    def __init__(
        self,
        store: DeviceCredentialStore | None = None,
        *,
        nonce_source: Callable[[], bytes] | None = None,
    ) -> None:
        self._store: DeviceCredentialStore = store if store is not None else InMemoryDeviceCredentialStore()
        self._nonce_source = nonce_source or (lambda: secrets.token_bytes(DEFAULT_NONCE_LENGTH))
        self._pending: tuple[bytes, bytes, str] | None = None
        self.challenges_issued = 0

    def begin(self, endpoint_id: bytes, relay_id: str) -> AuthChallenge:
        endpoint_id = _validate_endpoint_id(endpoint_id)
        _validate_relay_id(relay_id)
        nonce = _validate_nonce(self._nonce_source())
        self._pending = (nonce, endpoint_id, relay_id)
        self.challenges_issued += 1
        return AuthChallenge(nonce, self.method)

    def verify(self, proof: bytes) -> bool:
        pending, self._pending = self._pending, None
        if pending is None:
            return False
        nonce, endpoint_id, relay_id = pending
        secret = self._store.secret_for(endpoint_id)
        # Always run the MAC so unknown endpoints cost the same as bad proofs.
        key = secret if secret else b"\x00"
        expected = compute_proof(key, nonce, endpoint_id, relay_id)
        matched = hmac.compare_digest(expected, bytes(proof))
        return bool(secret) and matched

    @property
    def pending(self) -> bool:
        return self._pending is not None

    def __repr__(self) -> str:
        return f"ChallengeResponseAuthenticator(method={self.method!r}, pending={self.pending})"


__all__ = [
    "AUTH_FAILED_REASON",
    "DEVICE_HMAC_SHA256_V1",
    "DEFAULT_NONCE_LENGTH",
    "MIN_NONCE_LENGTH",
    "PROOF_LENGTH",
    "TRANSCRIPT_PREFIX",
    "ChallengeResponseAuthenticator",
    "DeviceCredentialStore",
    "InMemoryDeviceCredentialStore",
    "build_transcript",
    "compute_proof",
]
