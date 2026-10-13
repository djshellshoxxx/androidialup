# Repository engineering rules (AGENTS.md)

Applies to all changes in this repository, including AI-assisted changes. Read existing specs, architecture and tests before editing production code.

## Non-negotiable modularity rule

All **new code must be modular by default**, and existing code should be migrated incrementally. A module owns one cohesive responsibility, exposes a small documented contract, hides its implementation details, and depends on abstractions rather than unrelated concrete subsystems. Keep the public API stable until a tested migration is explicitly approved. Modularity is not a file-count goal: do not split small cohesive code solely to make more files.

## Safe refactor protocol

1. Confirm the actual default branch and current open PRs; avoid overwriting parallel work. Identify dependencies, callers, exported names, file formats and platform requirements before choosing a boundary.
2. Refactor one low-risk boundary at a time. Keep refactoring separate from new features, bug fixes and optimization unless a specific change is needed to preserve behavior.
3. Before moving code, write or extend characterization tests for *existing* observable behavior (input/output, errors, timing, state, public CLI/options and compatibility). Preserve tests for the old entry points while adding tests for the new module.
4. Prefer **move/extract/delegate**: put an existing responsibility behind a small interface and leave the old entry point as a thin compatibility adapter. Never silently rename public symbols, change defaults, replace protocols, or migrate user data during a no-behavior-change refactor.
5. Preserve ownership and lifecycle of sockets, file handles, USB devices, tasks and executors. Avoid changes to threading and concurrency unless separately specified, tested and reviewed. A green compile does not certify physical USB or Android lifecycle behavior.
6. Keep dependency direction clear: protocol/data models <- services/adapters <- application/CLI/UI. Platform implementations should be injected at boundaries. Side effects belong to adapters, not pure parsing/domain logic.
7. Run the focused tests, then the complete relevant CI matrix. For this repo: Linux Python prototype, native Windows Python, Android Core Tests, Android lint and release assembly. Check the final PR head and after-merge CI. Never claim on-device acceptance without device evidence.
8. Write brief module contract and migration notes. Merge only after required checks pass, with no unresolved defects; keep changes small and independently reversible.

## Code standards

- Use stable, explicit data types and narrow errors; validate external input at boundaries.
- Design for testability with lightweight dependency injection rather than global state, hidden singletons or unnecessary abstraction hierarchies.
- Limit shared mutable state, implicit ordering and circular imports. Prefer composition; keep helpers private unless reuse is justified.
- Keep comments for intent, constraints, invariants and non-obvious behavior; avoid commenting every obvious line.
- Do not bring blocking IO, heavy allocations, logging, or locks into real-time paths if audio/DSP paths are ever added.
- Security boundaries (authentication, TLS, credential handling, host permissions) must remain at least as strict as before a refactor.

## Relevant modules

See `docs/architecture/MODULARIZATION_ROADMAP.md` for current boundaries, dependencies, safe first steps and deferred high-risk work. Follow the project's existing normative protocol specs over generic refactoring preferences.
