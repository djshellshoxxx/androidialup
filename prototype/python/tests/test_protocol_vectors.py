import os
import unittest

from androidialup_protocol.frame import FrameKind, decode_frame, encode_frame
from androidialup_protocol.messages import decode_payload, encode_payload, kind_for_message

_TOOLS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tools")

import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("gen_adup_vectors", os.path.join(_TOOLS, "gen_adup_vectors.py"))
gen_adup_vectors = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen_adup_vectors)


def _load_vectors():
    with open(gen_adup_vectors.VECTORS_PATH, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            name, kind, hexdata = line.split("\t")
            yield name, int(kind), bytes.fromhex(hexdata)


class ProtocolVectorTests(unittest.TestCase):
    def test_committed_vectors_are_up_to_date(self):
        with open(gen_adup_vectors.VECTORS_PATH, "r", encoding="utf-8", newline="") as fh:
            committed = fh.read()
        self.assertEqual(committed, gen_adup_vectors.render(),
                         "tests/vectors/adup_v1_vectors.txt is stale; rerun tools/gen_adup_vectors.py")

    def test_generation_is_deterministic(self):
        self.assertEqual(gen_adup_vectors.render(), gen_adup_vectors.render())

    def test_every_payload_vector_round_trips(self):
        count = 0
        for name, kind_int, data in _load_vectors():
            if name.startswith("frame."):
                continue
            kind = FrameKind(kind_int)
            message = decode_payload(kind, data)
            self.assertEqual(kind_for_message(message), kind, name)
            self.assertEqual(encode_payload(message), data, name)
            count += 1
        self.assertGreaterEqual(count, 19)

    def test_every_frame_vector_round_trips(self):
        count = 0
        for name, kind_int, data in _load_vectors():
            if not name.startswith("frame."):
                continue
            frame, consumed = decode_frame(data)
            self.assertEqual(consumed, len(data), name)
            self.assertEqual(int(frame.kind), kind_int, name)
            message = decode_payload(frame.kind, frame.payload)
            self.assertEqual(encode_payload(message), frame.payload, name)
            self.assertEqual(encode_frame(frame), data, name)
            count += 1
        self.assertGreaterEqual(count, 1)

    def test_every_message_kind_is_covered(self):
        covered = {kind_int for name, kind_int, _ in _load_vectors() if not name.startswith("frame.")}
        for kind in FrameKind:
            if kind.value >= 60:
                continue  # incoming-call kinds are reserved, no payload codec yet
            self.assertIn(int(kind), covered, kind.name)

    def test_vector_names_are_unique(self):
        names = [name for name, _, _ in _load_vectors()]
        self.assertEqual(len(names), len(set(names)))


if __name__ == "__main__":
    unittest.main()
