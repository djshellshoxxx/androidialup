package io.circuitdrift.androidialup.modem;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * V.250-style line parser for the Beta 0.1 command set (S1 AT/DTE sections 2, 4 and 6).
 *
 * <p>The parser receives one command line without its terminator, validates the whole line and
 * returns an immutable command list. Any syntax problem raises {@link AtParseException} and no
 * command is returned, which gives the engine the "no partial application" guarantee.
 */
public final class AtLineParser {

    /** Maximum command line length in bytes, excluding the terminator. */
    public static final int MAX_COMMAND_LINE = 512;

    /** Maximum dial target length in UTF-8 bytes. */
    public static final int MAX_DIAL_TARGET_UTF8 = 256;

    private static final Set<String> PLUS_NAMES = Set.of("MODE", "NET", "DIAG");

    /**
     * Parses a complete command line.
     *
     * @param line the raw line bytes without the CR terminator
     * @return an unmodifiable list of commands; a bare {@code AT} yields one {@link AtCommand.Attention}
     * @throws AtParseException if the line is overlength, not UTF-8, lacks the AT prefix or contains
     *     an unknown or malformed command
     */
    public List<AtCommand> parse(byte[] line) {
        if (line.length > MAX_COMMAND_LINE) {
            throw new AtParseException("AT command line exceeds 512 bytes");
        }
        String text = decodeUtf8(line);
        if (text.length() < 2 || !text.substring(0, 2).equalsIgnoreCase("AT")) {
            throw new AtParseException("command line must begin with AT");
        }
        List<AtCommand> commands = new Cursor(text).parseBody();
        if (commands.isEmpty()) {
            return List.of(new AtCommand.Attention());
        }
        return List.copyOf(commands);
    }

    private static String decodeUtf8(byte[] line) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new AtParseException("AT command line is not valid UTF-8", e);
        }
    }

    /** Single-pass cursor over the decoded text after the AT prefix. */
    private static final class Cursor {
        private final String text;
        private int i = 2;

        Cursor(String text) {
            this.text = text;
        }

        List<AtCommand> parseBody() {
            List<AtCommand> commands = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (i >= text.length()) {
                    return commands;
                }
                char token = Character.toUpperCase(text.charAt(i));
                switch (token) {
                    case 'E', 'Q', 'V' -> commands.add(parseToggle(token));
                    case 'Z' -> {
                        i++;
                        commands.add(new AtCommand.Reset());
                    }
                    case '&' -> commands.add(parseAmpersand());
                    case 'H' -> commands.add(parseHangup());
                    case 'A' -> {
                        i++;
                        commands.add(new AtCommand.Answer());
                    }
                    case 'O' -> {
                        i++;
                        commands.add(new AtCommand.Online());
                    }
                    case 'I' -> commands.add(parseIdentify());
                    case 'S' -> commands.add(parseSRegister());
                    case 'D' -> commands.add(parseDial());
                    case '+' -> commands.add(parsePlus());
                    default -> throw new AtParseException("unknown command token '" + text.charAt(i) + "'");
                }
            }
        }

        private void skipWhitespace() {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
        }

        private boolean atDigit() {
            return i < text.length() && isAsciiDigit(text.charAt(i));
        }

        private static boolean isAsciiDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private int readDigits(String error) {
            int start = i;
            while (atDigit()) {
                i++;
            }
            if (start == i) {
                throw new AtParseException(error);
            }
            try {
                return Integer.parseInt(text.substring(start, i));
            } catch (NumberFormatException e) {
                throw new AtParseException("numeric value out of range", e);
            }
        }

        private AtCommand parseToggle(char token) {
            i++;
            skipWhitespace();
            if (i >= text.length() || (text.charAt(i) != '0' && text.charAt(i) != '1')) {
                throw new AtParseException(token + " requires 0 or 1");
            }
            boolean enabled = text.charAt(i) == '1';
            i++;
            return switch (token) {
                case 'E' -> new AtCommand.Echo(enabled);
                case 'Q' -> new AtCommand.Quiet(enabled);
                default -> new AtCommand.Verbose(enabled);
            };
        }

        private AtCommand parseAmpersand() {
            if (i + 1 >= text.length() || Character.toUpperCase(text.charAt(i + 1)) != 'F') {
                throw new AtParseException("unknown & command");
            }
            i += 2;
            return new AtCommand.Factory();
        }

        private AtCommand parseHangup() {
            i++;
            if (atDigit()) {
                if (text.charAt(i) != '0') {
                    throw new AtParseException("only H0 is supported");
                }
                i++;
            }
            return new AtCommand.Hangup();
        }

        private AtCommand parseIdentify() {
            i++;
            int page = 0;
            if (atDigit()) {
                page = text.charAt(i) - '0';
                if (page > 4) {
                    throw new AtParseException("ATI page must be 0..4");
                }
                i++;
            }
            return new AtCommand.Identify(page);
        }

        private AtCommand parseSRegister() {
            i++;
            int register = readDigits("S command requires a register number");
            skipWhitespace();
            if (i >= text.length()) {
                throw new AtParseException("S command requires ? or =value");
            }
            char op = text.charAt(i);
            if (op == '?') {
                i++;
                return new AtCommand.SQuery(register);
            }
            if (op == '=') {
                i++;
                skipWhitespace();
                int value = readDigits("S register set requires a numeric value");
                return new AtCommand.SSet(register, value);
            }
            throw new AtParseException("S command requires ? or =value");
        }

        private AtCommand parseDial() {
            i++;
            skipWhitespace();
            if (i < text.length()) {
                char modifier = Character.toUpperCase(text.charAt(i));
                if (modifier == 'T' || modifier == 'P') {
                    i++;
                }
            }
            String target = text.substring(i).trim();
            if (target.isEmpty()) {
                throw new AtParseException("D command requires a target");
            }
            if (target.indexOf(';') >= 0) {
                throw new AtParseException("dial semicolon syntax is not supported");
            }
            if (target.getBytes(StandardCharsets.UTF_8).length > MAX_DIAL_TARGET_UTF8) {
                throw new AtParseException("dial target exceeds 256 UTF-8 bytes");
            }
            i = text.length();
            return new AtCommand.Dial(target);
        }

        private AtCommand parsePlus() {
            i++;
            int nameStart = i;
            while (i < text.length() && (Character.isLetter(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
            String name = text.substring(nameStart, i).toUpperCase();
            if (!PLUS_NAMES.contains(name)) {
                throw new AtParseException("unsupported extended command +" + name);
            }
            skipWhitespace();
            if (i >= text.length()) {
                throw new AtParseException("+" + name + " requires ? or =value");
            }
            char op = text.charAt(i);
            if (op == '?') {
                i++;
                skipWhitespace();
                if (i != text.length()) {
                    throw new AtParseException("extended query must terminate the command line");
                }
                return new AtCommand.Plus(name, AtCommand.Plus.Operation.QUERY, null);
            }
            if (op == '=') {
                String value = text.substring(i + 1).trim();
                if (value.isEmpty()) {
                    throw new AtParseException("+" + name + " set requires a value");
                }
                i = text.length();
                return new AtCommand.Plus(name, AtCommand.Plus.Operation.SET, value);
            }
            throw new AtParseException("+" + name + " requires ? or =value");
        }
    }
}
