package io.circuitdrift.androidialup.modem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Mirrors prototype/python/tests/test_at_engine.py. */
class AtEngineTest {

    private final AtLineParser parser = new AtLineParser();
    private final AtEngine engine = new AtEngine("test-build");

    private AtExecution execute(String text) {
        return execute(text, AtContext.DEFAULT);
    }

    private AtExecution execute(String text, AtContext context) {
        return engine.execute(parser.parse(text.getBytes(StandardCharsets.UTF_8)), context);
    }

    @Test
    void factoryDefaultsAndSRegisters() {
        ModemProfile profile = engine.profile();
        assertTrue(profile.echo());
        assertFalse(profile.quiet());
        assertTrue(profile.verbose());
        assertEquals(ModemMode.AUTO, profile.mode());
        assertEquals(NetworkPolicy.AUTO, profile.networkPolicy());
        assertFalse(profile.extendedDiagnostics());
        assertEquals(
                Map.of(0, 0, 2, 43, 3, 13, 4, 10, 5, 8, 7, 60, 10, 14, 12, 50),
                profile.sRegisters());
        assertEquals(50, profile.sRegister(ModemProfile.S12_GUARD_TIME));
    }

    @Test
    void echoQuietVerboseAndNumericResult() {
        AtExecution result = execute("ATE0V0");
        assertFalse(engine.profile().echo());
        assertFalse(engine.profile().verbose());
        assertEquals(List.of("0"), result.outputLines());
        AtExecution quiet = execute("ATQ1");
        assertEquals(List.of(), quiet.outputLines());
        assertEquals(Optional.of(ResultCode.OK), quiet.terminalResult());
        assertTrue(engine.profile().quiet());
        assertEquals(List.of(), execute("ATI0").outputLines());
    }

    @Test
    void resultCodeNumbersAndText() {
        assertEquals(0, ResultCode.OK.number());
        assertEquals(1, ResultCode.CONNECT.number());
        assertEquals(2, ResultCode.RING.number());
        assertEquals(3, ResultCode.NO_CARRIER.number());
        assertEquals(4, ResultCode.ERROR.number());
        assertEquals(6, ResultCode.NO_DIALTONE.number());
        assertEquals(7, ResultCode.BUSY.number());
        assertEquals(8, ResultCode.NO_ANSWER.number());
        assertEquals("NO CARRIER", ResultCode.NO_CARRIER.text());
        assertEquals("NO DIALTONE", engine.formatResult(ResultCode.NO_DIALTONE));
        execute("ATV0");
        assertEquals("6", engine.formatResult(ResultCode.NO_DIALTONE));
    }

    @Test
    void sRegisterQuerySetAndUnknownRegister() {
        assertEquals(List.of("OK"), execute("ATS12=25").outputLines());
        assertEquals(List.of("25", "OK"), execute("ATS12?").outputLines());
        AtExecution unknown = execute("ATS99?");
        assertEquals(Optional.of(ResultCode.ERROR), unknown.terminalResult());
        assertEquals(Optional.of(ResultCode.ERROR), execute("ATS99=1").terminalResult());
    }

    @Test
    void sRegisterBoundsRejectedWithoutMutatingValue() {
        int old = engine.profile().sRegister(2);
        AtExecution result = execute("ATS2=999");
        assertEquals(Optional.of(ResultCode.ERROR), result.terminalResult());
        assertEquals(List.of("ERROR"), result.outputLines());
        assertEquals(old, engine.profile().sRegister(2));
    }

    @Test
    void runtimeFailureStopsExecutionWithOneFinalResult() {
        AtExecution result = execute("ATS12=25S99?E0");
        assertEquals(List.of("ERROR"), result.outputLines());
        assertEquals(25, engine.profile().sRegister(12));
        assertTrue(engine.profile().echo(), "E0 after the failing command must not apply");
    }

    @Test
    void identificationPages() {
        assertEquals(List.of("AndroidDialup", "OK"), execute("ATI0").outputLines());
        assertEquals(List.of("AndroidDialup", "OK"), execute("ATI").outputLines());
        assertEquals(List.of("Beta 0.1", "OK"), execute("ATI1").outputLines());
        assertEquals(List.of("protocol=1", "OK"), execute("ATI2").outputLines());
        assertEquals(List.of("test-build", "OK"), execute("ATI3").outputLines());
        assertTrue(execute("ATI4").outputLines().get(0).contains("BYTE_RELAY"));
    }

    @Test
    void modeQuerySetAndActiveCallRejection() {
        assertEquals(List.of("+MODE: AUTO", "OK"), execute("AT+MODE?").outputLines());
        execute("AT+MODE=BYTE_RELAY");
        assertEquals(ModemMode.BYTE_RELAY, engine.profile().mode());
        AtExecution rejected =
                execute("AT+MODE=PCM_VBD_EXPERIMENTAL", AtContext.DEFAULT.withActiveCall(true));
        assertEquals(Optional.of(ResultCode.ERROR), rejected.terminalResult());
        assertEquals(ModemMode.BYTE_RELAY, engine.profile().mode());
        assertEquals(Optional.of(ResultCode.ERROR), execute("AT+MODE=V152_RTP").terminalResult());
        assertEquals(ModemMode.BYTE_RELAY, engine.profile().mode());
    }

    @Test
    void networkPolicyQuerySet() {
        execute("AT+NET=PREFER_WIFI");
        assertEquals(NetworkPolicy.PREFER_WIFI, engine.profile().networkPolicy());
        AtExecution result =
                execute(
                        "AT+NET?",
                        AtContext.DEFAULT.withNetwork("WIFI", true, false));
        assertEquals(
                "+NET: policy=PREFER_WIFI,selected=WIFI,validated=1,metered=0",
                result.outputLines().get(0));
        assertEquals(Optional.of(ResultCode.ERROR), execute("AT+NET=BOGUS").terminalResult());
    }

    @Test
    void diagSetAndQuery() {
        execute("AT+DIAG=1");
        assertTrue(engine.profile().extendedDiagnostics());
        AtExecution result =
                execute("AT+DIAG?", AtContext.DEFAULT.withStates("COMMAND", "IDLE"));
        assertEquals("+DIAG: modem=COMMAND,session=IDLE,extended=1", result.outputLines().get(0));
        assertEquals(Optional.of(ResultCode.ERROR), execute("AT+DIAG=2").terminalResult());
        assertTrue(engine.profile().extendedDiagnostics());
    }

    @Test
    void factoryAndResetRestoreDefaults() {
        execute("ATE0");
        execute("ATS12=1");
        execute("AT+MODE=BYTE_RELAY");
        execute("AT&F");
        assertTrue(engine.profile().echo());
        assertEquals(50, engine.profile().sRegister(12));
        assertEquals(ModemMode.AUTO, engine.profile().mode());
        execute("ATE0");
        AtExecution reset = execute("ATZ", AtContext.DEFAULT.withActiveCall(true));
        assertTrue(engine.profile().echo());
        assertEquals(
                List.of(AtEffectType.HANGUP),
                reset.effects().stream().map(AtEffect::type).toList());
        assertEquals(List.of(), execute("ATZ").effects());
    }

    @Test
    void dialHangupAnswerOnlineAreEffects() {
        AtExecution dial = execute("ATDloopback");
        assertEquals(List.of(), dial.outputLines());
        assertEquals(Optional.empty(), dial.terminalResult());
        assertEquals(AtEffectType.DIAL, dial.effects().get(0).type());
        assertEquals("loopback", dial.effects().get(0).value());
        AtExecution hang = execute("ATH");
        assertEquals(AtEffectType.HANGUP, hang.effects().get(0).type());
        assertEquals(List.of("OK"), hang.outputLines());
        AtExecution answer = execute("ATA");
        assertEquals(AtEffectType.ANSWER, answer.effects().get(0).type());
        AtExecution online =
                execute("ATO", AtContext.DEFAULT.withActiveCall(true).withOnlineCommand(true));
        assertEquals(AtEffectType.RESUME_ONLINE, online.effects().get(0).type());
        assertEquals(List.of("CONNECT"), online.outputLines());
        AtExecution onlineRejected = execute("ATO");
        assertEquals(List.of("ERROR"), onlineRejected.outputLines());
        assertEquals(List.of(), onlineRejected.effects());
    }

    @Test
    void parserErrorPreventsAnyEngineMutation() {
        boolean oldEcho = engine.profile().echo();
        assertThrows(AtParseException.class, () -> execute("ATE0X"));
        assertEquals(oldEcho, engine.profile().echo());
    }

    @Test
    void executionResultsAreImmutable() {
        AtExecution result = execute("ATI0");
        assertThrows(UnsupportedOperationException.class, () -> result.outputLines().add("x"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.effects().add(new AtEffect(AtEffectType.HANGUP, null)));
    }
}
