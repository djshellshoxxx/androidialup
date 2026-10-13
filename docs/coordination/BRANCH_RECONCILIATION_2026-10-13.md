# Outstanding branch reconciliation — 2026-10-13

Base branch inspected: `main` at `a3969ce483d9007d7d854bbc636c22fc3e69e7ab`. At inspection, the repository had **zero open PRs** and **15 other branch refs**. None had a validated new PR ready for an automatic merge.

Git histories may show a branch as "diverged" even after a squash merge: the squash commit contains its reviewed changes but is not descended from the original branch head. **Do not directly merge an obsolete branch into main just because its ahead count is nonzero.** This can replace current security/resource-bound changes with older code or produce duplicate commits. Preserve branches; only clean up after ownership and equivalence review.

| Branch ref | Prior PR | Status and action |
| --- | --- | --- |
| `refactor/modular-aoa-host-discovery-20261013` | [#24](https://github.com/djshellshoxxx/androidialup/pull/24) | Merged (squash); no remerge |
| `fix/usb-dte-close-test-scheduling-20261013` | [#23](https://github.com/djshellshoxxx/androidialup/pull/23) | Merged (squash); no remerge |
| `audit/build-and-windows-qa-20261013` | [#22](https://github.com/djshellshoxxx/androidialup/pull/22) | Merged (squash); no remerge |
| `audit/fix-usb-dte-input-bounds-20261013` | [#21](https://github.com/djshellshoxxx/androidialup/pull/21) | Merged (squash); no remerge |
| `fix/android-settings-null-network-policy-20261012` | [#20](https://github.com/djshellshoxxx/androidialup/pull/20) | Merged; no remerge |
| `fix/relay-tls-handshake-deadline-20261012` | [#19](https://github.com/djshellshoxxx/androidialup/pull/19) | Merged; no remerge |
| `fix/java-dte-resource-bounds-20261012` | [#18](https://github.com/djshellshoxxx/androidialup/pull/18) | Merged; no remerge |
| `audit/relay-backend-failure-hardening-20261012` | [#17](https://github.com/djshellshoxxx/androidialup/pull/17) | Merged; no remerge |
| `qa/protocol-stream-batching-20261012` | [#10](https://github.com/djshellshoxxx/androidialup/pull/10) | Merged; no remerge |
| `codex/androidialup-api26-keystore-merge` | [#9](https://github.com/djshellshoxxx/androidialup/pull/9) | Merged; contains API 26 and Keystore consolidation |
| `codex/i2-aoa-service` | [#8](https://github.com/djshellshoxxx/androidialup/pull/8) | Merged; branch is wholly behind main |
| `codex/i2-usb-dte-foundation` | [#6](https://github.com/djshellshoxxx/androidialup/pull/6) | Merged; no remerge |
| `claude/gifted-shannon-q2sjwr` | [#4](https://github.com/djshellshoxxx/androidialup/pull/4) | Merged; extensive old divergence, unsafe to replay |
| `codex/api26-core-compat` | [#5](https://github.com/djshellshoxxx/androidialup/pull/5) | **Closed, not merged**; all four touched paths also included in merged consolidated PR #9. No independently identified unmerged behavior at file-scope; do not blindly merge. |
| `codex/keystore-device-secret` | [#7](https://github.com/djshellshoxxx/androidialup/pull/7) | **Closed, not merged**; all six touched paths also included in merged consolidated PR #9. The combined PR added additional Keystore tests; no independently identified unmerged feature at file-scope. Do not blindly merge. |

This assessment compares PR states and the touched file paths, **not** a proof that every old commit is byte-identical. If a specific feature is alleged missing, review its PR diff against current `main` and introduce a narrowly scoped new PR with regression tests. Keep current security and platform fixes intact.

## Future branch/merge safety

1. Inspect open PRs, owners, and actions before starting. Create a short-lived branch from fresh `main` for each independent migration.
2. Before merging, verify the exact PR head SHA and passing tests on that head. Include Linux/Windows Python and Android debug/release/lint when applicable.
3. Do not merge or delete branches merely due to `ahead_by > 0` when their work has already been squash-merged. No branches were deleted or force-pushed during this reconciliation.
4. After merge, inspect `main` runs and record any outstanding hardware-only acceptance separately.
