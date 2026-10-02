package io.circuitdrift.androidialup.modem;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Recording {@link SessionPort} used by the controller and end-to-end tests. */
final class FakeSessionPort implements SessionPort {
    final List<String> dials = new ArrayList<>();
    final List<byte[]> writes = new ArrayList<>();
    final List<String> hangups = new ArrayList<>();
    int answers;

    @Override
    public void dial(String target) {
        dials.add(target);
    }

    @Override
    public void writeData(byte[] data) {
        writes.add(data.clone());
    }

    @Override
    public void hangup(String reason) {
        hangups.add(reason);
    }

    @Override
    public void answer() {
        answers++;
    }

    byte[] written() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : writes) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    void clear() {
        dials.clear();
        writes.clear();
        hangups.clear();
        answers = 0;
    }
}
