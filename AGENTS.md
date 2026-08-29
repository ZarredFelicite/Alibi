# Project Agent Guidelines

## Testing Preferences

- Prefer Android emulator smoke tests for routine functional validation.
- Use controlled emulator A/B tests for relative CPU time, scheduler activity, I/O, and file-rotation comparisons.
- Do not interpret emulator results as physical battery-life measurements.
- Use a physical Android device only when hardware-specific behavior must be verified or the user explicitly requests it.
- Before installing on a physical device, preserve existing app installations and data; prefer a separately identified test build.

## Compatibility Scope

- Target current, modern Android versions by default.
- Do not spend effort supporting ancient Android/API versions unless the user explicitly requests that compatibility.
