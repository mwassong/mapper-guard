# Publishing Mapper Guard — the steps only you can do (~10 minutes)

Everything else is done for the current release (`v0.1.7`): 44/44 unit tests, ktlint clean, JetBrains'
verifier says **Compatible**, the real-codebase harness passes (0 false alarms, 6/6 planted errors caught), and
the installed check passes (fresh profile, real interpreter, uninstall), the 7-project corpus sweep has zero false
alarms, and mutation testing catches 25/25 deliberate bugs.

## Status (2026-11-03)
- Source is public at https://github.com/mwassong/mapper-guard (default branch `main`).
- Vendor profile `mike-wassong` created; website = https://github.com/mwassong (vendor-level, not per plugin).

## Upload (or update)
- File: the newest `build/distributions/mapper-guard-<version>.zip` — also copied to `C:\Users\mikew\Downloads`.
- Upload the newest zip. For later releases, use the plugin's page → **Upload Update**.
- **License:** MIT — `https://github.com/mwassong/mapper-guard/blob/main/LICENSE`
- **Source code:** `https://github.com/mwassong/mapper-guard`
- **Tags:** `Code Tools`, `Inspection`, `Database`, `Framework`. Hidden: unticked. Channel: default.

## Review
JetBrains reviews every new plugin and every update by hand. No answer in 3–4 working days →
marketplace@jetbrains.com.
