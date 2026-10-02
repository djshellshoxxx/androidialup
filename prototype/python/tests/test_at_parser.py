import unittest

from androidialup_modem.at_parser import (
    AnswerCommand,
    AttentionCommand,
    DialCommand,
    EchoCommand,
    FactoryCommand,
    HangupCommand,
    IdentifyCommand,
    OnlineCommand,
    ParserError,
    PlusCommand,
    QuietCommand,
    ResetCommand,
    SQueryCommand,
    SSetCommand,
    VerboseCommand,
    AtLineParser,
)


class AtLineParserTests(unittest.TestCase):
    def setUp(self):
        self.parser = AtLineParser()

    def test_bare_at_and_mixed_case(self):
        self.assertEqual(self.parser.parse(b"AT"), (AttentionCommand(),))
        self.assertEqual(self.parser.parse(b"aTe0v1"), (EchoCommand(False), VerboseCommand(True)))

    def test_chained_basic_commands(self):
        self.assertEqual(
            self.parser.parse(b"ATE1Q0V1H0"),
            (EchoCommand(True), QuietCommand(False), VerboseCommand(True), HangupCommand()),
        )

    def test_reset_factory_answer_online_and_identify(self):
        self.assertEqual(self.parser.parse(b"ATZ"), (ResetCommand(),))
        self.assertEqual(self.parser.parse(b"AT&F"), (FactoryCommand(),))
        self.assertEqual(self.parser.parse(b"ATA"), (AnswerCommand(),))
        self.assertEqual(self.parser.parse(b"ATO"), (OnlineCommand(),))
        self.assertEqual(self.parser.parse(b"ATI4"), (IdentifyCommand(4),))
        self.assertEqual(self.parser.parse(b"ATI"), (IdentifyCommand(0),))

    def test_s_register_query_and_set(self):
        self.assertEqual(self.parser.parse(b"ATS12?"), (SQueryCommand(12),))
        self.assertEqual(self.parser.parse(b"ATS2=43"), (SSetCommand(2, 43),))

    def test_extended_queries_and_sets(self):
        self.assertEqual(self.parser.parse(b"AT+MODE?"), (PlusCommand("MODE", "query", None),))
        self.assertEqual(self.parser.parse(b"AT+mode=byte_relay"), (PlusCommand("MODE", "set", "byte_relay"),))
        self.assertEqual(self.parser.parse(b"AT+NET=PREFER_WIFI"), (PlusCommand("NET", "set", "PREFER_WIFI"),))
        self.assertEqual(self.parser.parse(b"AT+DIAG=1"), (PlusCommand("DIAG", "set", "1"),))

    def test_dial_accepts_plain_tone_and_pulse_prefixes_without_changing_target(self):
        self.assertEqual(self.parser.parse(b"ATD5551212"), (DialCommand("5551212"),))
        self.assertEqual(self.parser.parse(b"ATDT5551212"), (DialCommand("5551212"),))
        self.assertEqual(self.parser.parse(b"ATDP5551212"), (DialCommand("5551212"),))

    def test_dial_target_preserves_case_and_utf8(self):
        self.assertEqual(self.parser.parse("ATDNode-Ä".encode()), (DialCommand("Node-Ä"),))

    def test_dial_semicolon_is_rejected(self):
        with self.assertRaisesRegex(ParserError, "semicolon"):
            self.parser.parse(b"ATD5551212;")

    def test_unknown_or_malformed_command_rejects_whole_line(self):
        for line in (b"ATX", b"ATE2", b"ATS?", b"AT+BOGUS?", b"AT+MODE", b"ATD"):
            with self.subTest(line=line), self.assertRaises(ParserError):
                self.parser.parse(line)

    def test_overlength_and_invalid_utf8_rejected(self):
        with self.assertRaisesRegex(ParserError, "512"):
            self.parser.parse(b"AT" + b"A" * 511)
        with self.assertRaisesRegex(ParserError, "UTF-8"):
            self.parser.parse(b"ATD\xff")

    def test_whitespace_between_basic_tokens_is_ignored(self):
        self.assertEqual(
            self.parser.parse(b"AT E0 Q1 V0"),
            (EchoCommand(False), QuietCommand(True), VerboseCommand(False)),
        )


if __name__ == "__main__":
    unittest.main()
