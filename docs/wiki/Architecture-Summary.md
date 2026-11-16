# Architecture Summary

From the README "Working conclusion for Beta 0.1" and the repository map.

- **IP-first core.** The primary path is the Android DTE/AT endpoint with an authenticated relay session over Wi-Fi or cellular data.
- **External SIP and dial-up control.** The [October 24 architecture amendment](../spec/S1_AMENDMENT_EXTERNAL_SIP_CONTROL_2026-10-24.md) selects Android as the control UI for a user-controlled server. Server-side modem equipment, media qualification and hardware acceptance are deferred. The earlier phone-side voice and modem paths remain research history.
- **Transport-independent protocol.** The wire protocol is written so that V.150.1-style modem relay and V.152 voice-band data can be added later.
- **Evidence policy.** Design decisions prefer official standards, vendor documentation, textbooks, academic work and mature open-source code, in that order (README).
- **Experiments before claims.** Interoperability is not claimed until the experiment plan's tests and external regression vectors pass.
