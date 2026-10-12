# XMicInject — Deto / Wevo

The repository preserves the accepted Deto v3 baseline and a separate Wevo-targeted build.

## ✅ Approved update: Wevo PC microphone audio

**Status:** User-approved Wevo update (2026-10-13).

- **Purpose:** Transfer computer microphone/PC audio to the Wevo Android app through the existing XMicInject audio-injection architecture.
- **Target Wevo package:** `com.all2chat.voip`
- **Installed module package:** `ae.wevo.xmicinject`
- **Display label:** `Wevo`
- **Audio base:** `stable-v3-working` at baseline commit `06814569ec379259478786a85d3a976a5dbcdbc3`.
- **Pinned upstream XMicInject source:** `7261a91dba76b3b94b0fa71061121257db3ed0a3`.
- **Audio patches retained:** `IpcClient.kt`, `PcmRingBuffer.kt` (250 ms buffer), and the stable Xposed AudioRecord hook logic. The Wevo patch changes the target package and log label only.
- **Build workflow:** [Build XMicInject-Wevo](https://github.com/mohameed12mo1-jpg/XMicInject-Deto/actions/workflows/build-xmicinject-wevo.yml).
- **Successful build run:** [Run #1 — success](https://github.com/mohameed12mo1-jpg/XMicInject-Deto/actions/runs/38322713361).
- **Build output:** [XMicInject-Wevo-debug artifact](https://github.com/mohameed12mo1-jpg/XMicInject-Deto/actions/runs/38322713361). GitHub Actions artifacts expire according to retention policy; rerun the workflow to produce a fresh APK.
- **Source branch:** [`wevo-v3-working`](https://github.com/mohameed12mo1-jpg/XMicInject-Deto/tree/wevo-v3-working).

### Important separation

The original `stable-v3-working` branch remains unchanged and retains the Deto baseline. Wevo-specific package preparation is in `tools/prepare_wevo.py`; the dedicated workflow builds Wevo separately. Do not replace the Deto branch with the Wevo target.

### Validation

The successful workflow built the APK, verified generated package metadata as `ae.wevo.xmicinject`, checked the APK archive, and uploaded the artifact. Actual installation and live audio injection must still be verified on the target phone/Wevo room.
