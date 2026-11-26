## Summary
<!-- Briefly describe what this PR changes and why. -->

## Related Issue(s)
<!-- Closes #... -->

## Scope & Type of Change
- [ ] Bug fix (non-breaking change)
- [ ] New device driver (`BatteryDevice` + `DriverCapabilities` + `ProtocolSafetyInfo`)
- [ ] UI / Taskbar Widget / i18n improvement
- [ ] Documentation / Packaging / Maintenance

## Verification
- [ ] `mvn clean verify` passes locally (`-Xlint:all,-serial,-processing`, JUnit tests, and SpotBugs)
- [ ] Tested in **Simulation Mode** (if UI/logic change)
- [ ] Tested with **physical hardware on Windows 11 x64** (list exact devices tested below; do not check if only simulated!)

### Devices / Environment Tested
- **Windows version (x64):**
- **Physical devices tested (if any):**

## HID Safety & Licensing Checklist
- [ ] No experimental HID writes, RGB/LED control bytes, DPI/EQ presets, or firmware commands were added
- [ ] Read-Only HID Mode (`readOnlyHidMode`) constraints are respected
- [ ] No personal data, raw USB captures, emails, Bluetooth MACs, or serial numbers are included
- [ ] All code complies with **GNU GPL v3.0** and any external references are documented in `THIRD-PARTY-NOTICES.md`
