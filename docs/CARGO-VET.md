# cargo-vet — supply-chain audit attestations

`cargo-vet` is the third leg of our Rust supply-chain story alongside
`cargo-deny` (license + duplicate bans) and `cargo-audit` (CVE database
checks). Where those two answer *"is this dep allowed?"* and *"is this dep
known to be vulnerable?"*, cargo-vet answers *"has any human we trust
actually read this dep's source?"*

The CI job in [.github/workflows/security.yml](../.github/workflows/security.yml)
is wired up but stays a no-op until the one-time bootstrap below lands.

## One-time bootstrap

Run once on the maintainer's machine, in a clean checkout:

```sh
# Install cargo-vet (one-time, takes ~3 minutes the first time)
cargo install cargo-vet --locked

# Initialize the supply-chain/ directory inside core/
cd core
cargo vet init
```

`cargo vet init` creates `core/supply-chain/` with three TOML files:

| File         | Purpose                                                              |
| ------------ | -------------------------------------------------------------------- |
| `config.toml`   | Vet behaviour, import sources, policy.                            |
| `audits.toml`   | Audits *you* perform locally. Initially empty.                    |
| `imports.toml`  | Cached mirror of imported vet stores (refreshed on `cargo vet`).  |

Edit `core/supply-chain/config.toml` and add the three standard import
sources so we inherit audit work already done by Google, Mozilla, and the
Bytecode Alliance / Embark:

```toml
[imports.google]
url = "https://raw.githubusercontent.com/google/supply-chain/main/audits.toml"

[imports.mozilla]
url = "https://raw.githubusercontent.com/mozilla/supply-chain/main/audits.toml"

[imports.embark]
url = "https://raw.githubusercontent.com/EmbarkStudios/rust-ecosystem/main/audits.toml"
```

Then run a first check:

```sh
cargo vet --manifest-path core/Cargo.toml check
```

On a fresh repo with ~300 transitive crates, this will report a long list of
deps that nobody in the imported stores has audited. **Don't try to audit
each one by hand.** Instead, baseline them as exemptions:

```sh
cargo vet --manifest-path core/Cargo.toml regenerate exemptions
```

This rewrites `audits.toml` to mark every currently-pinned crate version as
"exempt from audit requirement, accepted as-is at this baseline." The CI
gate then enforces only *new or upgraded* deps going forward, which is the
point — the goal isn't to retroactively audit the world, it's to make sure
the next dep that sneaks in via `cargo add` or a transitive bump gets human
eyes.

Commit the whole `core/supply-chain/` directory (typically a few hundred KB
of TOML):

```sh
git add core/supply-chain
git commit -m "supply-chain: bootstrap cargo-vet baseline"
```

The next CI run will see `core/supply-chain/config.toml` exist and start
running `cargo vet check` for real.

## Day-to-day workflow

### When you add a new dep

`cargo add foo` may pull in transitive deps that need to be vetted. After
adding:

```sh
cargo vet --manifest-path core/Cargo.toml check
```

For each newly-flagged crate, you have three options:

1. **Inherit an audit** from a trusted source you import (Google, Mozilla,
   Embark). `cargo vet suggest` will tell you if there is one available.

2. **Certify it yourself** after reading the source:

   ```sh
   cargo vet --manifest-path core/Cargo.toml certify foo 1.2.3
   ```

   This walks you through criteria (`safe-to-deploy`, `safe-to-run`) and
   appends the attestation to `audits.toml`.

3. **Exempt it** if you don't have time to audit but want to unblock the PR
   (use sparingly — exemptions are tech debt):

   ```sh
   cargo vet --manifest-path core/Cargo.toml exempt foo 1.2.3
   ```

### When you bump a dep

Same flow. A new version means a new audit requirement; the previous audit
covers only the previous version.

### Pruning exemptions

Periodically, run:

```sh
cargo vet --manifest-path core/Cargo.toml prune
```

This removes exemptions that are no longer needed (because an upstream
audit covers the version now, or because the dep was dropped).

## What cargo-vet does NOT do

- **It does not run any code.** It only reads source and your
  attestation TOML. Safe to run on PRs.
- **It does not check for CVEs.** That's cargo-audit's job. They overlap
  zero — a popular crate can be vetted-clean and have an active CVE; an
  unvetted crate can be CVE-free.
- **It does not enforce licenses.** That's cargo-deny.
- **It does not audit anything for you.** Inheriting from Google/Mozilla/
  Embark is the trust-by-proxy shortcut; certifying anything beyond that
  is a manual code-reading exercise.

## References

- [Mozilla's cargo-vet user guide](https://mozilla.github.io/cargo-vet/)
  — canonical docs.
- [Google's supply-chain audit store](https://github.com/google/supply-chain)
  — what we import; largest single corpus.
- [Mozilla's supply-chain audit store](https://github.com/mozilla/supply-chain)
  — second-largest; focused on Firefox / Servo deps.
- [Embark Studios audits](https://github.com/EmbarkStudios/rust-ecosystem)
  — game-industry Rust deps; smaller but covers a different slice.
