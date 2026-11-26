# Code Signing & Windows SmartScreen Policy

Executable installers (`.exe` / `.msi`) built for Windows x64 trigger **Microsoft Defender SmartScreen** warnings when they are not signed with a certificate trusted by the Windows Trusted Root Certification Authorities store.

This document explains how **Wireless Battery Monitor** handles binary integrity today and compares legitimate open-source signing paths for future releases.

---

## 1. Current Policy: Transparent Unsigned Releases + SHA-256 Verification

Until an open-source signing pipeline is configured, official releases on [GitHub Releases](https://github.com/kybiy/Wireless-Device-Battery-Monitor/releases) are **unsigned**:

1. Every release publishes `SHA256SUMS.txt` alongside `WirelessBattery-<version>.exe` and `WirelessBattery-<version>-portable-win-x64.zip`.
2. Users should verify the download integrity in PowerShell before running:
   ```powershell
   Get-FileHash .\WirelessBattery-1.2.0.exe -Algorithm SHA256
   Get-FileHash .\WirelessBattery-1.2.0-portable-win-x64.zip -Algorithm SHA256
   ```
3. Only if the hash matches `SHA256SUMS.txt` from the official repository should the user click **More info** → **Run anyway** on SmartScreen.

> [!CAUTION]
> **Never disable Windows SmartScreen or Windows Defender**, and **never use self-signed certificates disguised as trusted CA signing**. Self-signed certificates do not satisfy SmartScreen reputation checks and installing untrusted root certificates weakens system security.

---

## 2. Comparison of Code Signing Options for Open-Source Projects

| Option | Cost | Trust & SmartScreen Impact | CI Compatibility & Requirements |
| --- | --- | --- | --- |
| **SignPath Foundation** *(OSS Tier)* | **Free** for qualifying open-source projects | Trusted CA signatures; builds SmartScreen reputation over releases | Designed for GitHub Actions (`signpath/github-action-submit-signing-request`); strict governance requirements |
| **Microsoft Azure Trusted Signing** | Paid monthly tier (check current Azure availability/eligibility) | Microsoft-backed short-lived certificates; immediate trust integration with Windows | Integrates with GitHub Actions via OIDC / Azure CLI; requires identity validation and Azure subscription |
| **Commercial OV / EV Certificate** | Paid annual fee + hardware token / cloud HSM | EV accelerates SmartScreen reputation; OV builds reputation over time | Requires HSM / FIPS-compliant token (e.g., DigiCert KeyLocker, Sectigo, GlobalSign) for unattended CI |
| **Self-Signed Certificate** | Free | **Not trusted** by Windows unless users manually install a root CA (unsafe) | **Prohibited** for public distribution |

---

## 3. SignPath Foundation Free OSS Eligibility & Workflow

If the project applies for the [SignPath Foundation](https://signpath.org/) free open-source program, all of the following eligibility and governance conditions apply:

1. **OSI-approved license:** This project is licensed under **GNU GPL v3.0** (`LICENSE`).
2. **No proprietary code:** All source code and build scripts are public and open source.
3. **Actively maintained public GitHub repository with releases:** Public repository with tagged releases and transparent changelogs.
4. **MFA enforced:** Two-factor authentication (MFA) must be enabled for all organization/repository members and approvers.
5. **Role separation (Authors / Reviewers / Approvers):** SignPath requires separating build artifact authors from human/automated signing reviewers and approvers so a compromised PR cannot self-sign.
6. **GitHub-hosted runners only:** Signing requests must originate from trusted GitHub-hosted CI runners (e.g., `windows-latest`), not self-hosted runners.

### Reference GitHub Actions Snippet (for future adoption)
Once a SignPath project, organization ID, and signing policy are provisioned, a release job running after `package-windows.ps1` uploads the unsigned artifact and submits a signing request:

```yaml
- name: Submit Windows Installer to SignPath
  uses: signpath/github-action-submit-signing-request@v2 # pin to exact commit SHA in production
  with:
    api-token: ${{ secrets.SIGNPATH_API_TOKEN }}
    organization-id: ${{ vars.SIGNPATH_ORG_ID }}
    project-slug: wireless-battery-monitor
    signing-policy-slug: release-signing
    artifact-configuration-slug: windows-installer
    github-artifact-id: ${{ steps.upload-unsigned.outputs.artifact-id }}
    wait-for-completion: true
    output-artifact-directory: dist/signed
```

*(Note: No SignPath secrets are currently configured in `.github/workflows/release.yml`; all actions in `.github/workflows/` remain pinned to immutable commit SHAs.)*
