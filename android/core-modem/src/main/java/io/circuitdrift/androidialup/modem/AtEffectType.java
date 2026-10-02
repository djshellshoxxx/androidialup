package io.circuitdrift.androidialup.modem;

/** Side effects an executed command line requests from the modem state owner. */
public enum AtEffectType {
    DIAL,
    HANGUP,
    ANSWER,
    RESUME_ONLINE
}
