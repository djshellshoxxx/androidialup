package io.circuitdrift.androidialup.modem;

/** Sink for bytes the modem sends toward the DTE (echo, result lines, online data). */
@FunctionalInterface
public interface DteWriter {
    void write(byte[] data);
}
