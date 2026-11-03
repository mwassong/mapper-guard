# Publishing Mapper Guard — the steps only you can do (~10 minutes)

Everything else is done: 10/10 unit tests, JetBrains' verifier says **Compatible**, the real-codebase
harness passes, and the release is tagged `v0.1.0`.

## 1. Put the source on GitHub (needed for the licence/source link and the vendor website)
Claude was blocked from uploading code to GitHub without your explicit OK. Either tell Claude
"go ahead and create the public repo", or run this yourself:

```bash
cd /home/yamu/sqlalchemy-pycharm && gh repo create mwassong/mapper-guard --public --source . --remote origin --push && git push origin v0.1.0
```

Your Gmail is NOT in the commit history — commits use GitHub's private no-reply address.

## 2. Create the JetBrains Marketplace vendor profile (free)
1. Sign in at https://plugins.jetbrains.com with a JetBrains account (create one if needed).
2. Account menu → **Upload plugin**. The first time, you'll be asked to:
   - accept the **Developer Agreement**;
   - create a **Vendor profile** — type *Individual*, display name `Mike Wassong`,
     website `https://github.com/mwassong/mapper-guard`, and a working email you're happy to show;
   - declare **non-trader** (EEA rule) — correct for a free plugin with no revenue.

## 3. Upload
- File: `/home/yamu/sqlalchemy-pycharm/build/distributions/mapper-guard-0.1.0.zip`
  (Windows path: `\\wsl$\Ubuntu\home\yamu\sqlalchemy-pycharm\build\distributions\`)
- **License:** MIT — link `https://github.com/mwassong/mapper-guard/blob/main/LICENSE`
- **Source code:** `https://github.com/mwassong/mapper-guard`
- **Tags:** `Code Tools`, `Inspection`, `Database`, `Framework`
- Leave **Hidden** unchecked. No custom release channel.

## 4. Wait for review
JetBrains reviews every new plugin by hand. If there's no answer in 3–4 working days, email
marketplace@jetbrains.com. Approval emails come to the address on your JetBrains account.

## What happens after
Once approved, tell Claude: it will watch downloads, and the plan is to add a paid Pro tier (Alembic
migration checks, a model diagram) only once real people are installing the free version.
