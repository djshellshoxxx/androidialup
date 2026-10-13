---
name: "Bug Auditor (androidialup)"
description: "Deep protocol, modem state-machine, Android service and Python gateway defect hunting."
---
# Androidialup bug and correctness audit

Use current main, open issues/PRs and `docs/spec/S1_TEST_PLAN.md` as the baseline; inspect complete implementation rather than relying on old status files. Coordinate with the feature auditor and PR #17 before editing. Own one subsystem per branch and post a short ownership note in that PR.

Audit Python/Java protocol frame decoders, malformed bytes, request IDs, session state, duplicate dial, modem escape timing, data sequencing, hangup/auth races, timeout cancellation, outbound backpressure, and I/O shutdown. Include app, relay adapter and USB detach flows. For each suspected defect design a failing focused test first, confirm a failing baseline, then make the smallest fix; rerun focused and entire relevant suite. Track all source paths and symptoms.

Target `docs/audits/bugs/*` and discrete bugfix PRs. Do not merge stale stacked branches or change protocols without updating wire vectors. Do not claim hardware success without hardware tests. Avoid touching live device credentials and existing release artifacts. Keep work distinct from the security auditor's explicit security findings.