# XMicInject-Deto

Clean Deto-specific build wrapper around the upstream `n00b-labs/XMicInject` project.

## Target

- Deto package: `ae.deto.app`
- LSPosed module applicationId: `ae.deto.xmicinject`
- Module label: `Deto`
- V6 transport: unchanged direct V4 TCP transport on port `38673`
- Security/integrity/VPN behavior: untouched
- Microphone use during testing: disabled by the test procedure

## Design

This build intentionally does **not** carry the previous V7 runtime experiments.

`XMicHook.kt`:
- loads only for `ae.deto.app`
- uses the native `AudioRecord` read hooks for Deto
- leaves upstream resampling/injection logic intact

`IpcClient.kt`:
- retains the existing low-latency direct V4 transport
- listens on `0.0.0.0:38673`
- stores provider PCM as 16 kHz mono PCM16

The GitHub Actions workflow clones the clean upstream project, applies these two files,
changes only the installed module id/label, and builds the debug APK.

## Build

Push this repository to GitHub, then run:

Actions -> Build XMicInject-Deto -> Run workflow

The resulting artifact is `XMicInject-Deto-debug.apk`.

## LSPosed

Install the APK, enable the module, and add **Deto (`ae.deto.app`)** to its scope.
Reboot/reload as required by the current LSPosed setup.

## Source basis

The upstream project is an LSPosed module built around `AudioRecord.read()` and contains
`XMicHook`, `PcmRingBuffer`, `IpcClient`, `UplinkSender`, and `AudioResampler`.
