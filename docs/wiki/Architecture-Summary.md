# Architecture Summary

From the README "Working conclusion for Beta 0.1" and the repository map.

- **IP-first core.** The primary path is the Android DTE/AT endpoint with an authenticated relay session over Wi-Fi or cellular data.
- **Voice-band data over IP** (secondary) uses RTP/UDP-style timing and a gateway that can end in a hardware modem, an analog interface or software DSP.
- **Direct cellular voice-channel modem operation** (experimental) only on privileged or custom builds or hardware with access to call audio.
- **Transport-independent protocol.** The wire protocol is written so that V.150.1-style modem relay and V.152 voice-band data can be added later.
- **Evidence policy.** Design decisions prefer official standards, vendor documentation, textbooks, academic work and mature open-source code, in that order (README).
- **Experiments before claims.** Interoperability is not claimed until the experiment plan's tests and external regression vectors pass.
