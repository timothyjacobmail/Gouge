# Selected beta release

User selection: A1 A2 A3 A4 A5 A6 A8 A9 A10 B1 B2 B3 B4 B5 B6 B7 B10 B11 C3 C6 C9 D3 D11 E2 E5 F1 F3 I1 I3 I5 I7.

Baseline: 1.9.9 (22), 391 JVM tests. Existing uncommitted VPN, temporary Images, and Play Store fixes must be preserved. No stable credential migration or package rename.

## Safety boundaries

- Master beta switch and every optional feature default off.
- Isolated SQLite database; stable settings, task credentials and app locks remain authoritative.
- Delayed, scoped approval for beta relaxations. Commitments refuse early cancellation. Remote commands affect beta profiles only.
- No downloaded code executed or GPL implementation copied. Package/resource IDs are interoperability facts; selectors are best-effort and explicitly labelled beta.
- Network backend adoption depends on measurements. Evaluation can conclude that the existing backend should stay.
- No permanent commitment or real user lock changes during validation.

## Work checklist

- [x] A1–A10 selected: profiles, schedules, budgets, sessions, launches, Pomodoro, commitments, bonus
- [x] B1–B11 selected: short feeds, UI selectors, path/proxy rules, browser policies
- [x] C3/C6/C9: sentence, grayscale, break reminders
- [x] D3/D11: live counter and progress widget
- [x] E2/E5: DNS scope and local list management
- [x] F1/F3: authenticated local companion and scoped commands
- [x] I1/I3/I7: serialized reconciliation, independent recovery, transactional storage
- [x] I5: Go backend evaluation (no consistent win; no production backend replacement)
- [x] JVM tests (418), isolated Android persistence/TLS tests (9), compatibility lint
- [x] Beta-off phone smoke test and unchanged credential fingerprint (62 fields)
- [x] Version bump: 1.10.0-beta.1 / 23
- [x] Final phone installation/service check; release source prepared for GitHub push

The selected implementation is complete. Experimental limitations and validation are recorded in the release documentation.

Implementation details and limitations: [beta-features.md](../beta-features.md). Android isolated persistence/TLS suite passed 9 tests after correcting Keystore TLS signing permissions. Selected features remain off on the real phone; no real profile commitment or remote override was created during testing.
