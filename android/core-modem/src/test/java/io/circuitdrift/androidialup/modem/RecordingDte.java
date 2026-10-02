package io.circuitdrift.androidialup.modem;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Records every write the controller makes toward the DTE. */
final class RecordingDte implements DteWriter {
    final List<byte[]> writes = new ArrayList<>();

    @Override
    public void write(byte[] data) {
        writes.add(data.clone());
    }

    byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : writes) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    String text() {
        return new String(bytes(), StandardCharsets.ISO_8859_1);
    }

    /** Only the CRLF-terminated writes, as text, mirroring the Python state_result_lines helper. */
    List<String> resultLines() {
        List<String> lines = new ArrayList<>();
        for (byte[] chunk : writes) {
            String s = new String(chunk, StandardCharsets.ISO_8859_1);
            if (s.endsWith("\r\n")) {
                lines.add(s);
            }
        }
        return lines;
    }

    void clear() {
        writes.clear();
    }
}
