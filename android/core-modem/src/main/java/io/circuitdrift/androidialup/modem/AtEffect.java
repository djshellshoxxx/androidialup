package io.circuitdrift.androidialup.modem;

import java.util.Objects;

/**
 * One requested side effect. {@code value} carries the dial target for {@link AtEffectType#DIAL}
 * and is {@code null} otherwise.
 */
public record AtEffect(AtEffectType type, String value) {
    public AtEffect {
        Objects.requireNonNull(type, "type");
    }

    public static AtEffect of(AtEffectType type) {
        return new AtEffect(type, null);
    }
}
