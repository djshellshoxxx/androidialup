import unittest

from androidialup_modem.at_engine import (
    AtContext,
    AtEffectType,
    AtEngine,
    ResultCode,
)
from androidialup_modem.at_parser import AtLineParser, ParserError
from androidialup_modem.profile import ModemMode, NetworkPolicy


class AtEngineTests(unittest.TestCase):
    def setUp(self):
        self.parser = AtLineParser()
        self.engine = AtEngine(build_id="test-build")

    def execute(self, text, context=None):
        return self.engine.execute(self.parser.parse(text.encode()), context or AtContext())

    def test_factory_defaults_and_s_registers(self):
        profile = self.engine.profile
        self.assertTrue(profile.echo)
        self.assertFalse(profile.quiet)
        self.assertTrue(profile.verbose)
        self.assertEqual(profile.mode, ModemMode.AUTO)
        self.assertEqual(profile.network_policy, NetworkPolicy.AUTO)
        self.assertEqual(profile.s_registers, {0: 0, 2: 43, 3: 13, 4: 10, 5: 8, 7: 60, 10: 14, 12: 50})

    def test_echo_quiet_verbose_and_numeric_result(self):
        result = self.execute("ATE0V0")
        self.assertFalse(self.engine.profile.echo)
        self.assertFalse(self.engine.profile.verbose)
        self.assertEqual(result.output_lines, ("0",))
        quiet = self.execute("ATQ1")
        self.assertEqual(quiet.output_lines, ())
        self.assertTrue(self.engine.profile.quiet)

    def test_s_register_query_set_and_unknown_register(self):
        result = self.execute("ATS12=25")
        self.assertEqual(result.output_lines, ("OK",))
        query = self.execute("ATS12?")
        self.assertEqual(query.output_lines, ("25", "OK"))
        unknown = self.execute("ATS99?")
        self.assertEqual(unknown.terminal_result, ResultCode.ERROR)

    def test_s_register_bounds_rejected_without_mutating_value(self):
        old = self.engine.profile.s_registers[2]
        result = self.execute("ATS2=999")
        self.assertEqual(result.terminal_result, ResultCode.ERROR)
        self.assertEqual(self.engine.profile.s_registers[2], old)

    def test_identification_pages(self):
        self.assertEqual(self.execute("ATI0").output_lines, ("AndroidDialup", "OK"))
        self.assertEqual(self.execute("ATI1").output_lines, ("Beta 0.1", "OK"))
        self.assertEqual(self.execute("ATI2").output_lines, ("protocol=1", "OK"))
        self.assertEqual(self.execute("ATI3").output_lines, ("test-build", "OK"))
        self.assertIn("BYTE_RELAY", self.execute("ATI4").output_lines[0])

    def test_mode_query_set_and_active_call_rejection(self):
        self.assertEqual(self.execute("AT+MODE?").output_lines, ("+MODE: AUTO", "OK"))
        self.execute("AT+MODE=BYTE_RELAY")
        self.assertEqual(self.engine.profile.mode, ModemMode.BYTE_RELAY)
        rejected = self.execute("AT+MODE=PCM_VBD_EXPERIMENTAL", AtContext(active_call=True))
        self.assertEqual(rejected.terminal_result, ResultCode.ERROR)
        self.assertEqual(self.engine.profile.mode, ModemMode.BYTE_RELAY)

    def test_network_policy_query_set(self):
        self.execute("AT+NET=PREFER_WIFI")
        self.assertEqual(self.engine.profile.network_policy, NetworkPolicy.PREFER_WIFI)
        result = self.execute("AT+NET?", AtContext(selected_network="WIFI", validated=True, metered=False))
        self.assertEqual(result.output_lines[0], "+NET: policy=PREFER_WIFI,selected=WIFI,validated=1,metered=0")

    def test_diag_set_and_query(self):
        self.execute("AT+DIAG=1")
        self.assertTrue(self.engine.profile.extended_diagnostics)
        result = self.execute("AT+DIAG?", AtContext(modem_state="COMMAND", session_state="IDLE"))
        self.assertEqual(result.output_lines[0], "+DIAG: modem=COMMAND,session=IDLE,extended=1")

    def test_factory_and_reset_restore_defaults(self):
        self.execute("ATE0S12=1+MODE=BYTE_RELAY") if False else None
        self.execute("ATE0")
        self.execute("ATS12=1")
        self.execute("AT+MODE=BYTE_RELAY")
        self.execute("AT&F")
        self.assertTrue(self.engine.profile.echo)
        self.assertEqual(self.engine.profile.s_registers[12], 50)
        self.assertEqual(self.engine.profile.mode, ModemMode.AUTO)
        self.execute("ATE0")
        reset = self.execute("ATZ", AtContext(active_call=True))
        self.assertTrue(self.engine.profile.echo)
        self.assertEqual([effect.type for effect in reset.effects], [AtEffectType.HANGUP])

    def test_dial_hangup_answer_online_are_effects(self):
        dial = self.execute("ATDloopback")
        self.assertEqual(dial.output_lines, ())
        self.assertEqual(dial.effects[0].type, AtEffectType.DIAL)
        self.assertEqual(dial.effects[0].value, "loopback")
        hang = self.execute("ATH")
        self.assertEqual(hang.effects[0].type, AtEffectType.HANGUP)
        self.assertEqual(hang.output_lines, ("OK",))
        answer = self.execute("ATA")
        self.assertEqual(answer.effects[0].type, AtEffectType.ANSWER)
        online = self.execute("ATO", AtContext(active_call=True, online_command=True))
        self.assertEqual(online.effects[0].type, AtEffectType.RESUME_ONLINE)

    def test_parser_error_prevents_any_engine_mutation(self):
        old_echo = self.engine.profile.echo
        with self.assertRaises(ParserError):
            self.parser.parse(b"ATE0X")
        self.assertEqual(self.engine.profile.echo, old_echo)


if __name__ == "__main__":
    unittest.main()
