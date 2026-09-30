# Battery-efficiency code audit

Audited revision: `9fc1c38`. Scope: continuous audio/video recording, optional encoded RAM capture, retention/storage, UI scheduling, notifications and resource lifetime.

The original findings below describe baseline `9fc1c38`. Source paths are relative to `app/src/main/java/app/myzel394/alibi/` unless stated otherwise; their original line references apply to that baseline.

## Implemented and verified — 2026-09-30

Branch: `fix/background-efficiency`, isolated worktree `.worktrees/background-efficiency`.

- Replaced unconditional service elapsed-time scheduling with a monotonic active-duration getter; only a STARTED recording UI refreshes once per second. Pause/stop freeze elapsed time and binding refreshes its snapshot.
- Removed the unread 900 ms audio-status coroutine without changing screen-awake behavior or UI design.
- Added a once-reconciled batch index, updated through internal/custom/legacy/scoped creation paths. Retention deletes only eligible indexed refs; unsuccessful deletions remain retryable. Scoped-video creation registers names rather than invalidating/relisting on every cycle.
- Gated RAM amplitude calculation on a volatile visualizer-demand flag. AAC frames use an internal ownership-transfer insertion while public insertion/snapshots retain defensive copies; sample rate and codec output metadata are reused.
- Fixed save protection with independent synchronized tokens covering all numeric batches while a live save is in flight, including paused recordings. Tokens release through the captured service despite state changes. Cleanup preserves other saves, newer batches and the current interval batch; inactive-save cleanup remains intact.

Validation: declared AGP 8.3.0 / Gradle 8.4 debug build passed using an SDK 34 Nix environment; **21 targeted unit tests passed**, plus **2 Android filesystem/service regression tests** covering held/overlapping save locks and current-batch cleanup. Only the isolated API 35 emulator was targeted; no physical app/data was modified.

Matched file-audio run, activity STOPPED, 16 kHz mono / 96 kbps, five-minute intervals, 60-second measurement:

| App-process proxy | Baseline | Candidate |
|---|---:|---:|
| CPU time | 0.21 s | 0.03 s |
| Scheduler timeslices, summed over threads | 713 | 7 |
| Recording-byte growth | 730,825 B | 730,825 B |

Re-entry, pause and resume passed. RAM mode created no numeric files during 30 seconds in the background; the completed AAC export fully decoded (43.136 seconds, 16 kHz mono, approximately 96 kbps). A 10-second interval / 60-second retention smoke kept six batches while expiring old files and continuing current-file growth.

Limits: one short emulator pair, approximately matched warmup (nine-second difference), debug builds and quiet synthetic input. App-process counters exclude Android media-service encoding/I/O; no rotation occurred within the paired sample. **These numbers are not battery-life gains or whole-device power measurements.** Nonzero visualizer response was not established on the quiet emulator, though return/pause/resume and encoded output worked. Scoped/custom backend indexing was inspected and pure index failure/retry tests passed; filesystem/runtime retention tests used internal storage, not every provider or camera.

Temporary evidence: `/tmp/alibi-bg-baseline-results.json`, `/tmp/alibi-bg-candidate-results.json`, `/tmp/alibi-bg-candidate-build.log`, `/tmp/alibi-bg-candidate-instrumentation.log`; Gradle XML results remain in the worktree's ignored `app/build/test-results/testDebugUnitTest/`.

Workflow corrections: an initial SDK expression requested unnecessary emulator/image dependencies and hit its bounded timeout; it was narrowed to compile tools and the existing emulator reused. A worker initially edited four main-checkout files; its task-owned edits were backed up under `/tmp/alibi-root-save-worker-backup` and main restored, with final corrections retained in the worktree. Suggested safeguards are a dependency dry-run before provisioning and asserting the allowed worktree prefix before every edit. No agent-instruction changes were made. A scratch export test originally stopped before the picker completed; correcting that sequencing produced a fully decoded export without production changes.

## Background-only priorities (user's clarified scope)

Only background battery consumption matters. Display timeout, screen-visible animation and visible selector enumeration are therefore not optimization priorities; the broader findings below are retained as reference.

1. **Default file audio:** replace the unconditional one-second elapsed-time executor with monotonic timestamp-derived time and lifecycle-gated UI refresh. This removes confirmed app scheduling work, but likely savings are modest relative to continuous microphone/encoding work.
2. **Backgrounded activity:** remove the unused 900 ms audio-status coroutine; it can remain scheduled when the activity is hidden but its composition survives. No recomposition-frequency claim is warranted.
3. **Storage:** prioritize incremental pruning only if actual settings use short intervals, long retention or an expensive storage provider. Five-minute/default retention scans are unlikely to dominate.
4. **Optional RAM audio:** avoid hidden-visualizer amplitude calculation and duplicate encoded-frame allocation. Measure real read/codec scheduling before changing batching; RAM has not been shown to consume less energy than MediaRecorder.
5. **Background video / paused video:** investigate retained camera/torch resources and offer explicit fidelity presets if video is part of the workload. These are hardware-dependent and separate from audio-mode background consumption.

There is no confirmed runaway steady-state loop in default file-audio capture. The RAM EOS spin is stop-only; remux/re-encoding is save-only. Neither should be sold as a fix for continuous background drain. Save protection remains a correctness prerequisite if retention changes are pursued. Actual background energy savings require measurement; this audit provides code-derived candidates, not battery-life estimates.

## Original broad audit assessment

The default file-audio path is already reasonably restrained: 16 kHz audio, 96 kbps, five-minute segments, no background visualizer polling, and a system notification chronometer. I found avoidable work, but not evidence of a catastrophic always-on loop in ordinary file recording.

The most promising improvements depend on use case:

- **Recording screen left visible:** allow screen timeout; the app explicitly keeps the display awake, including while paused.
- **Screen-off/background audio:** replace the unconditional one-second elapsed-time executor with timestamp-derived time and lifecycle-gated display refreshes. Likely a modest gain, not a transformation of microphone/encoder cost.
- **Short segments or many retained files:** make pruning incremental instead of rescanning the batch store on each rotation.
- **Experimental RAM mode:** reduce frame allocations, avoid unused amplitude computation, and bound the shutdown drain without busy-spinning. Measure actual capture/codec scheduling before changing batching.
- **Video:** a lower-quality preset may matter more than bookkeeping optimizations, but it changes recording fidelity. Paused camera/preview resource lifetime needs runtime verification.

**Separate prerequisite:** active-save locking currently requests deletion of files it should protect. Fix and test this before changing retention.

## Current operating profile

| Item | Current behavior | Implication |
|---|---|---|
| Audio defaults | 16 kHz, 96 kbps; RAM mode off (`db/AppSettings.kt:202–211`) | Keep efficient defaults; RAM is not established as lower-power. |
| Retention | 15 minutes; five-minute segment interval (`db/AppSettings.kt:35–37`) | 12 scheduled rotations/hour after the immediate initial cycle. |
| Elapsed time | One scheduled callback/second (`services/RecorderService.kt:147–159`) | Approximately 3,600 executions/hour during recording, including without a visible observer. |
| Audio status ticker | An unused clock write every 900 ms (`ui/components/RecorderScreen/organisms/AudioRecordingStatus.kt:43–50`) | Approximately 4,000 coroutine resumptions/hour while composed. |
| Visualizer | Samples every 100 ms only while enabled; UI enables at STARTED, disables at STOPPED/disposal | Existing background optimization; do not mistake this for continuous background polling. |
| Notification | System chronometer; posts on recording/pause transitions | No app-driven per-second notification rebuild loop. |
| Video defaults | Unspecified bitrate/frame rate; fallback to `Quality.HIGHEST` | Potentially expensive video configuration, depending on device. |

The supported minimum interval is 10 seconds and maximum retention is 10 days. In combination that represents about 86,400 retained segments, whereas defaults retain only about three. Storage optimization priority should reflect the user's actual configuration.

## Ranked actionable improvements

### 1. Allow display timeout on recording screens

**Confirmed behavior; potentially largest screen-visible opportunity.**

`ui/utils/views.kt:7–15` sets `keepScreenOn = true` until disposal. Both `AudioRecordingStatus.kt:52` and `VideoRecordingStatus.kt:54` call it unconditionally. `ui/screens/RecorderScreen.kt:103–106` chooses these screens whenever the service reference is present (`ui/models/BaseRecorderModel.kt:33–35`), so pause does not remove the effect.

Recommendation: make staying awake an explicit preference, allowing normal timeout for background-oriented recording. At minimum, do not keep the screen awake while paused. This is not a background wake lock: hiding the activity is a different case, and this finding does not establish screen-off drain.

Validation: with a short configured display timeout, check active and paused screens under both preference states; ensure recording and notification controls continue after the display turns off. Confirm only the relevant flag is changed, without disrupting processing-dialog behavior.

### 2. Replace the unconditional elapsed-time executor

**Confirmed background work; expected gain modest and unmeasured.**

`services/RecorderService.kt:40–56, 147–159` maintains time by incrementing a counter every second. The task runs even when `onRecordingTimeChange` has no useful visible consumer. `ui/models/BaseRecorderModel.kt:91–95` also keeps forwarding updates into UI state without visibility gating.

Recommendation: derive active elapsed time from a monotonic clock, accumulated completed recording periods, and the current start timestamp. Refresh displayed time only while a lifecycle-visible UI needs it. Preserve pause exclusion and rebind synchronization; retaining callbacks without gating would preserve much of the current work. Do not replace this with an Android alarm or stop the foreground recorder.

The notification already uses its own chronometer, so it does not require this executor to refresh every second. Its anchor must remain consistent with accumulated time after pause/resume.

Validation: compare scheduler activity and app CPU during equal screen-off recordings; verify pause/resume, rebind, elapsed-time labels, progress and notification anchors. These tasks are not proof of one hardware wakeup per second.

### 3. Delete the unused audio-status clock loop

**Confirmed redundant work; very low implementation risk.**

`AudioRecordingStatus.kt:43–50` writes `now = LocalDateTime.now()` every 900 ms but never reads `now`. Remove the state and effect. Unlike a lifecycle-gated clock, this loop continues while its composable remains present, including paused/background activity states.

There is no subscribed read of this state, so **the writes do not establish 1.1 recompositions/second**. The actual established waste is the coroutine resumption, clock-object work and state writes. Recording-time display already has a separate source.

Validation: inspect that no consumer exists and smoke-check elapsed-time/save controls. This does not require a broad test suite or an energy benchmark.

### 4. Make batch pruning incremental

**Confirmed scans; importance scales with interval and file count.**

`services/IntervalRecorderService.kt:52–58, 120–128` calls deletion on each cycle once its deletion endpoint becomes positive. `helpers/BatchesFolder.kt:366–425` lists internal/custom directories or queries matching MediaStore entries to locate deletable files. MediaStore enumeration requests every column (`:77–117`).

Recommendation: after fixing save protection, track created batch identifiers/URIs and the oldest successfully pruned index. Delete only newly expired unprotected batches. Reconcile once at initialization/recovery or after failed deletion rather than assuming the in-memory index survives process death. Use a narrow MediaStore projection where compatible with existing callbacks. Do not remove save-protected batches or lose recoverable old recordings.

At defaults the directory is small and scans occur roughly every five minutes, so likely gains are small. With 10-second intervals or long retention, the same design is much more consequential. Custom-folder providers may add provider IPC/I/O beyond local directory costs.

Validation: compare deletion/query counts and CPU/I/O at defaults and a representative short interval; verify retention across restart, deletion failures and an active save spanning rotation. Avoid constructing the theoretical maximum solely for this audit.

### 5. Reduce optional RAM capture work without changing audio

**Confirmed hot-path work; likely small individually.**

- `services/RamAudioCapture.kt:217–227` allocates/copies each AAC output, then `EncodedAudioFrameRingBuffer.kt:30–36` copies it again. Use an internal ownership-transfer insertion path to retain the fresh array once, keeping public defensive-copy semantics and immutable snapshots.
- `RamAudioCapture.kt:207–209` allocates a new `MediaCodec.BufferInfo` on every drain call. Reuse a worker-owned instance.
- `RamAudioCapture.kt:151–169` repeatedly obtains the fixed sample rate. Cache it; this is a micro-optimization, not a significant battery finding.
- `RamAudioCapture.kt:151, 239–247` scans PCM for amplitude on every positive read, even though service-side amplitude polling is disabled when the visualizer is hidden. A thread-safe visibility/request signal could skip peak calculation when unused without skipping encoding. Default work is approximately 16,000 samples/second; it is a simple allocation-free scan, so profile before prioritizing it.

At 16 kHz AAC-LC with typical 1,024-sample access units, expect approximately 15.6 encoded units/second; verify the actual codec output. The extra payload copy is only around 12 kB/second at 96 kbps. Removing it is sensible allocation hygiene, but does not justify predicting a large battery gain.

Validation: compare allocations/GC and app CPU for a fixed recording; verify frame/timestamp parity, ring limits, snapshot immutability, pause/resume and visualizer reactivation.

### 6. Stop RAM capture without a tight EOS drain spin

**Confirmed conditional busy-spin; shutdown-only, not steady-state drain.**

`RamAudioCapture.kt:177–195` waits up to two seconds for encoder EOS. Its final loop repeatedly calls `drainEncoder()`, which uses a zero-timeout output dequeue (`:207–214`). Delayed or missing EOS can therefore make it spin until the deadline.

Recommendation: use a bounded waiting dequeue during the final drain, capped by the remaining deadline. Keep normal capture draining nonblocking; do not discard final frames or weaken worker-join/resource-release guarantees.

Validation: exercise delayed EOS with a test seam or reproducible codec behavior; compare shutdown CPU and latency and verify tail frames. Normal codecs may finish quickly, so routine recording savings could be negligible.

## Measure-before-changing opportunities

### RAM read/codec batching

Input dequeue can wait 10 ms (`RamAudioCapture.kt:144, 265`); AudioRecord's blocking read normally paces work. Read size is bounded by codec capacity and the configured audio buffer (`:140–149`). Input-buffer starvation could cause repeated timeout/drain iterations, but this audit did not establish that it happens materially.

Measure PCM bytes/read, read duration, input-dequeue misses, output cadence and selected codec. Then consider batching or callback-driven scheduling only if the trace supports it. Larger buffers trade latency and reliability; callbacks do not inherently prove lower power. Do not force a supposedly hardware AAC codec based on an assumed energy advantage.

### File-mode recorder rotation

`AudioRecorderService.kt:54–72, 318–379` prepares a recorder, stops/releases the previous recorder and starts the new segment each cycle. Five-minute defaults give 12 rotations/hour; a 10-second interval gives 360/hour. Longer intervals can reduce restart overhead but trade retention granularity and save latency. Any continuous-output/next-file design is a larger recording change, not a free optimization; validate boundary integrity before considering it.

### Avoid repeated device enumeration during composition

`ui/components/RecorderScreen/molecules/MicrophoneSelection.kt:60–68` queries microphones and filters the result directly during composition; `ui/components/RecorderScreen/organisms/VideoRecordingStatus.kt:127–131` queries cameras/characteristics likewise. Cache lists outside time-driven rendering and refresh when the selector opens or device availability changes. Actual query frequency is unmeasured: Compose can skip unchanged children, so do not assume every timer or amplitude update repeats these calls. Validate query counts and microphone reconnect visibility; this is a lower-priority visible-UI optimization, not established screen-off waste.

### Video quality, paused camera and preview lifetime

- `VideoRecorderService.kt:161–180` falls back to highest quality. Offer an explicitly selected lower-energy resolution/frame-rate preset rather than silently reducing fidelity. Compare matched scene, duration, quality and audio settings on the relevant hardware before claiming gains.
- Pause stops recording but does not unbind the camera (`:102–106, 185–204`). This may retain sensor/ISP resources, but actual CameraX/device behavior is unverified. Measure before unbinding; rebind changes resume latency and failure modes.
- `ui/components/RecorderScreen/atoms/CameraPreview.kt:27–53` binds preview without explicit disposal unbinding. Check whether dismissing the preparation preview leaves a use case bound. If so, unbind only the owned preview, not every use case; provider-wide `unbindAll()` can interfere with service recording.
- Pause does not explicitly turn off torch. If it remains lit, power is being spent on deliberate lighting, not encoder bookkeeping. Turning it off on pause requires an explicit behavior decision and synchronized UI/resume state.

## Correctness prerequisite: active-save deletion

This is not a battery finding, but it constrains safe pruning optimization.

`IntervalRecorderService.kt:38–48` records `lockedIndex = counter`. Deletion then computes:

```kotlin
val earliestCounter = Math.max(counter - timeMultiplier, lockedIndex ?: 0)
batchesFolder.deleteRecordings(0..earliestCounter)
```

The lock becomes a **deletion floor**, the opposite of its documented protection purpose. Example: lock at counter 3, next cycle 4, multiplier 3; deletion requests counters `0..3`, instead of just the ordinary expired range `0..1`.

The active-save call site locks before obtaining recording information and concatenating (`ui/components/RecorderScreen/organisms/RecorderEventsHandler.kt:152–192`). File-audio pruning happens before the previous recorder is stopped. Successful deletion before FFmpeg opens/reaches a batch can therefore disrupt a save. Actual failure is not guaranteed: provider deletion, already-open descriptors and cached output affect the outcome.

Affected scope: interval-batched audio/video active saves. RAM audio disables interval batches and does not exercise periodic pruning in this mode. Implement explicit protected save ranges/snapshots with synchronization; blindly switching `max` to `min` is not sufficient proof of correct semantics. Validate saving across a retention boundary before efficiency work touches this logic.

## What is already efficient / not a priority

- Visualizer polling is disabled on STOPPED UI lifecycle and disposal (`RealTimeAudioVisualizer.kt:76–95`; `AudioRecorderService.kt:165–180`). Preserve this optimization.
- Notifications use the system chronometer and update on state transitions (`RecorderNotificationHelper.kt:113–142`; `RecorderService.kt:187–202`), not every second.
- RAM retains bounded encoded AAC rather than raw PCM; materializing save snapshots is save-time work, not continuous capture I/O.
- Concatenation/remux/re-encode is explicit save work (`helpers/BatchesFolder.kt:252–295`); video tries stream copy before re-encoding. Optimize this only if saving is a frequent workload.
- Diagnostics are bounded and cycle checkpoints occur every 12 rotations, approximately hourly at defaults. No per-sample or per-second diagnostic logging was found.
- No explicit app wake lock or wake-alarm loop was found in inspected recorder paths/manifest. This does not prove platform audio/camera components hold no wake locks.

## Suggested implementation order and validation

1. Fix/test save protection independently before changing retention.
2. Remove the unused ticker; make screen-awake behavior explicit, starting with pause.
3. Replace elapsed-time bookkeeping and gate UI refresh by visibility.
4. Implement incremental pruning if short intervals/custom storage matter in actual use.
5. Bundle small RAM allocation changes; investigate batching only after a trace demonstrates a meaningful scheduling issue.
6. Evaluate camera lifetime and video energy presets separately with explicit fidelity/resume decisions.

Use separately identified test builds and controlled emulator A/B runs for app CPU time, scheduling, allocations and I/O. Keep sample rate, bitrate, retention, interval, storage backend, duration and visibility fixed; compare file and RAM capture separately. Verify playable saved audio/video, retained duration, timestamps, pause/resume, end-of-stream and save-across-rotation integrity. **Emulator results are not physical battery-life measurements.** A physical-device energy comparison is a separate approved step when needed, preserving existing app data/installations.

External support directly retrieved during the audit: [AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord) documents blocking reads and the minimum-buffer reliability caveat; [lifecycle-aware coroutines](https://developer.android.com/topic/libraries/architecture/coroutines) supports stopping UI collection at STOPPED. Attempts to fetch other official codec/notification pages timed out; recommendations above rely on inspected application code, not unread-source power claims.
