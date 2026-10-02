package io.circuitdrift.androidialup.modem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mirrors prototype/python/tests/test_at_parser.py. */
class AtLineParserTest {

    private final AtLineParser parser = new AtLineParser();

    private List<AtCommand> parse(String text) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void bareAtAndMixedCase() {
        assertEquals(List.of(new AtCommand.Attention()), parse("AT"));
        assertEquals(
                List.of(new AtCommand.Echo(false), new AtCommand.Verbose(true)),
                parse("aTe0v1"));
    }

    @Test
    void chainedBasicCommands() {
        assertEquals(
                List.of(
                        new AtCommand.Echo(true),
                        new AtCommand.Quiet(false),
                        new AtCommand.Verbose(true),
                        new AtCommand.Hangup()),
                parse("ATE1Q0V1H0"));
    }

    @Test
    void resetFactoryAnswerOnlineAndIdentify() {
        assertEquals(List.of(new AtCommand.Reset()), parse("ATZ"));
        assertEquals(List.of(new AtCommand.Factory()), parse("AT&F"));
        assertEquals(List.of(new AtCommand.Answer()), parse("ATA"));
        assertEquals(List.of(new AtCommand.Online()), parse("ATO"));
        assertEquals(List.of(new AtCommand.Identify(4)), parse("ATI4"));
        assertEquals(List.of(new AtCommand.Identify(0)), parse("ATI"));
    }

    @Test
    void sRegisterQueryAndSet() {
        assertEquals(List.of(new AtCommand.SQuery(12)), parse("ATS12?"));
        assertEquals(List.of(new AtCommand.SSet(2, 43)), parse("ATS2=43"));
    }

    @Test
    void extendedQueriesAndSets() {
        assertEquals(
                List.of(new AtCommand.Plus("MODE", AtCommand.Plus.Operation.QUERY, null)),
                parse("AT+MODE?"));
        assertEquals(
                List.of(new AtCommand.Plus("MODE", AtCommand.Plus.Operation.SET, "byte_relay")),
                parse("AT+mode=byte_relay"));
        assertEquals(
                List.of(new AtCommand.Plus("NET", AtCommand.Plus.Operation.SET, "PREFER_WIFI")),
                parse("AT+NET=PREFER_WIFI"));
        assertEquals(
                List.of(new AtCommand.Plus("DIAG", AtCommand.Plus.Operation.SET, "1")),
                parse("AT+DIAG=1"));
    }

    @Test
    void dialAcceptsPlainToneAndPulsePrefixesWithoutChangingTarget() {
        assertEquals(List.of(new AtCommand.Dial("5551212")), parse("ATD5551212"));
        assertEquals(List.of(new AtCommand.Dial("5551212")), parse("ATDT5551212"));
        assertEquals(List.of(new AtCommand.Dial("5551212")), parse("ATDP5551212"));
    }

    @Test
    void dialTargetPreservesCaseAndUtf8() {
        assertEquals(List.of(new AtCommand.Dial("Node-Ä")), parse("ATDNode-Ä"));
    }

    @Test
    void dialSemicolonIsRejected() {
        AtParseException error = assertThrows(AtParseException.class, () -> parse("ATD5551212;"));
        assertTrue(error.getMessage().contains("semicolon"));
    }

    @Test
    void dialTargetOver256Utf8BytesIsRejected() {
        String target = "Ä".repeat(128); // 256 UTF-8 bytes: accepted.
        assertEquals(List.of(new AtCommand.Dial(target)), parse("ATD" + target));
        AtParseException error =
                assertThrows(AtParseException.class, () -> parse("ATD" + target + "x"));
        assertTrue(error.getMessage().contains("256"));
    }

    @Test
    void unknownOrMalformedCommandRejectsWholeLine() {
        for (String line : List.of("ATX", "ATE2", "ATS?", "AT+BOGUS?", "AT+MODE", "ATD", "AT&X", "ATH1", "ATI5")) {
            assertThrows(AtParseException.class, () -> parse(line), line);
        }
    }

    @Test
    void nonAtPrefixIsRejected() {
        assertThrows(AtParseException.class, () -> parse("A"));
        assertThrows(AtParseException.class, () -> parse("XTE0"));
    }

    @Test
    void overlengthAndInvalidUtf8Rejected() {
        AtParseException overlength =
                assertThrows(AtParseException.class, () -> parse("AT" + "A".repeat(511)));
        assertTrue(overlength.getMessage().contains("512"));
        AtParseException utf8 =
                assertThrows(
                        AtParseException.class,
                        () -> parser.parse(new byte[] {'A', 'T', 'D', (byte) 0xFF}));
        assertTrue(utf8.getMessage().contains("UTF-8"));
    }

    @Test
    void whitespaceBetweenBasicTokensIsIgnored() {
        assertEquals(
                List.of(new AtCommand.Echo(false), new AtCommand.Quiet(true), new AtCommand.Verbose(false)),
                parse("AT E0 Q1 V0"));
    }

    @Test
    void parsedListIsImmutable() {
        List<AtCommand> commands = parse("ATE0");
        assertThrows(UnsupportedOperationException.class, () -> commands.add(new AtCommand.Attention()));
    }
}
