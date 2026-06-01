# Castla Remote Compositor Architecture

Castla is a remote Android workspace compositor, not phone-screen mirroring. The phone display and Tesla browser display remain independent by keeping at least one persistent VirtualDisplay alive for the remote environment.

Current production note:

- the long-term target architecture in `app/src/main/java/com/castla/mirror/compositor` still exists
- but the live production orchestration path is still centered on `MirrorForegroundService.MirroringPipeline`
- recent stability work has focused on stream generation resets, fresh launch preparation, SPS/PPS clearing/replay, and removing `tapOutside`

## 1. High-Level Architecture

The target shape is:

```text
Android persistent VD sessions
  -> MediaCodec H.264 encoders
  -> WebSocket stream transport
  -> browser runtime workers
  -> decoder backend
  -> browser compositor viewports
  -> touch / IME / focus feedback
```

Fullscreen and split transitions are browser compositor layout changes first. Android VirtualDisplays, tasks, and encoders are only resized, paused, or resumed when policy requires it.

## 2. Android Architecture

The new core lives under `app/src/main/java/com/castla/mirror/compositor`.

`DisplaySessionRegistry` owns N registered sessions and applies policy. `PersistentVirtualDisplaySession` models `VD != Surface != Encoder != Stream` by giving each layer its own restart path and lifecycle state.

`DisplaySessionRegistry` contains:

- `PersistentVirtualDisplaySession[]`
- `ResourcePolicyManager`
- `EncoderBudgetManager`
- `StreamPriorityManager`
- `LayoutCoordinator`

## 3. Frontend Architecture

The new frontend scaffold lives under `frontend/src` and uses Svelte only for UI/layout containers. Stream runtime code is framework-independent TypeScript.

```text
Svelte UI
  -> compositorStore
  -> StreamRuntime
  -> DecoderBackend
  -> VideoTransport / ControlTransport
```

## 4. Kotlin Implementation Structure

Key files:

- `DisplayTier.kt`: explicit ACTIVE, VISIBLE, SUSPENDED, PARKED policy tiers.
- `DisplaySessionRegistry.kt`: N-display registry and policy application.
- `PersistentVirtualDisplaySession.kt`: persistent VD session with independent encoder recovery.
- `LifecycleStateMachine.kt`: lifecycle transitions for VD, surface, encoder, stream, first frame, recovery.
- `StreamGenerationState.kt`: generation and first-frame metadata.
- `RemoteImeBridge.kt`: commit/composition/delete/finish IME command bridge.
- `AccessibilityFocusManager.kt`: focus tracking model for an AccessibilityService implementation.

## 5. Svelte Frontend Structure

`frontend/src` is arranged by runtime boundary:

- `runtime/`: stream runtime, generation tracking, health monitoring.
- `decoder/`: `DecoderBackend`, WebCodecs, JMuxer.
- `compositor/`: browser compositor coordinator.
- `transport/`: control and video sockets.
- `viewport/`: viewport math.
- `touch/`: browser-to-VD coordinate mapping.
- `ime/`: composition-safe text bridge.
- `stores/`: Svelte stores for UI state only.
- `workers/`: stream synchronization worker.
- `components/`: Svelte UI containers and overlays.

## 6. Stream Runtime Architecture

`StreamRuntime` owns control/video transports, frame dispatch, `GenerationTracker`, and `StreamHealthMonitor`. Svelte never receives raw frame queues.

## 7. Display Tiering System

`DisplayTier` maps to `StreamProfile`:

- `ACTIVE`: full encoder, full bitrate, full FPS.
- `VISIBLE`: encoder running with reduced bitrate/FPS.
- `SUSPENDED`: VD/app can remain alive, encoder released or paused.
- `PARKED`: minimal resource state.

## 8. Encoder Budget Manager

`EncoderBudgetManager.MAX_ACTIVE_ENCODERS = 2`. The registry can contain many sessions, but policy promotes only the highest-priority visible sessions to ACTIVE while preserving the primary session.

## 9. Worker Architecture

`frontend/src/workers/streamWorker.ts` validates frame ordering against generation metadata before frames reach decoders. The next migration step is to instantiate this worker from `StreamRuntime` and move decoder ownership into it for Tesla Browser memory stability.

## 10. Stream Synchronization Logic

The Android server now emits:

```json
{
  "type": "streamMetadata",
  "vdId": 1,
  "sessionId": "primary",
  "generation": 14,
  "width": 1920,
  "height": 1080,
  "streamReady": true,
  "firstFrameReady": true
}
```

The browser commits viewport layout only when `firstFrameReady` is true. Until then, the viewport stays pending to avoid stale frames and black-screen layout commits.

This rule is now important for restart stability as well:

- mirroring restart or browser reconnect starts a fresh generation
- first launch after restart must not reuse stale `firstFrameReady` assumptions
- same-app launch dedupe is bypassed once when fresh launch preparation is required
- fresh SPS/PPS and keyframe/IDR delivery are required before the browser commits the generation visually

## 11. State Machine Implementations

`LifecycleStateMachine` tracks:

`NEW -> VD_READY -> SURFACE_READY -> ENCODER_READY -> STREAM_READY -> WAITING_FIRST_FRAME -> RUNNING`

Any layer can move to `RECOVERING`, `SUSPENDED`, or `RELEASED` without implying that the other layers must be destroyed.

## 12. Embedded Server Architecture

`ServerLayer` separates the conceptual roles:

- `StaticAssetServer`
- `ApiServer`
- `WebSocketControlServer`
- `VideoStreamServer`

The existing `MirrorServer` remains the compatibility host while these boundaries are migrated out of the monolith.

## 13. Shared Protocol Structure

`frontend/src/protocol.ts` defines stream metadata, diagnostics, frame headers, and display tiers. Kotlin emits matching JSON from `MirrorServer`.

## 14. Touch Routing Implementation

`TouchRouter` maps browser coordinates through viewport contain math, letterboxing, and pane identity before sending normalized VD coordinates. Android continues injecting with `InputManager.injectInputEvent()` through the privileged service.

## 15. IME Architecture

The current preferred typing path is no longer "frontend IME proxy first".

Preferred path:

- Tesla / browser touch
- native Android focus on the target app inside the privileged VirtualDisplay
- Samsung Keyboard / Gboard rendered directly inside the trusted VD
- normal Android `InputConnection`
- target app

Fallback path:

- `ImeBridge` still exists and can send `commitText`, `setComposingText`, `deleteSurroundingText`, and `finishComposingText`
- `ControlSocket` still accepts these messages and maps them to text/composition injection paths
- this path is retained as a fallback, not removed entirely

Native IME-in-VD currently depends on the Shizuku-created trusted VirtualDisplay path plus local IME policy. The app-created `DisplayManager` capture path is not the preferred route for remote app typing.

`tapOutside` is no longer part of the IME protocol. Castla does not infer remote dismiss intent from viewport taps anymore; IME focus transitions are driven by normal `androidFocusChanged`, `onStartInput`, and `onFinishInput` lifecycle signals.

## 16. Accessibility Focus Manager

`AccessibilityFocusManager` is no longer the live primary path for remote text entry. The production direction is IME-session and native-keyboard centric. Accessibility-era focus heuristics remain legacy/supporting code, not the architectural center.

## 17. Stream Health Monitoring

`StreamHealthMonitor` tracks reconnect count, last frame time, stale frames, decoder stalls, and can hook `requestVideoFrameCallback()` where available.

## 18. Lifecycle-Safe Threading Model

Session mutations use coroutine `Mutex`. IME composition is serialized on a single-thread dispatcher. Encoder callbacks only broadcast frames and metadata, avoiding UI-state mutation on codec threads.

## 19. Samsung Compatibility Strategy

The architecture avoids frequent VD destruction and task relaunches. Samsung and One UI instability is handled by persistent VD sessions, surface replacement, encoder restart, bounded resize verification, and no raw phone-screen fallback when privileged VD recreation fails.

For Samsung Keyboard specifically:

- Castla can now render the native Samsung keyboard inside the trusted VD.
- Samsung Keyboard may choose split/general/floating layout based on its own state.
- Castla currently treats keyboard layout mode as keyboard-owned behavior rather than something guaranteed by Castla policy.

## 20. Recovery/Reconnect Strategy

WebSocket reconnects request keyframes without destroying the VD. Encoder failure is handled by rebuilding the encoder surface and reattaching it to the existing VD. Browser layout waits for first frame in the new generation.

Additional current constraints:

- recovery must not force-stop apps
- recovery must not use automatic app relaunch loops
- watchdog and inject-reject handling are soft-recovery only
- first app launch after mirroring restart uses fresh launch preparation so stale display/stream state does not block Maps or other same-app launches
- verbose diagnostics are runtime-toggled and default off

## 21. Performance Optimization Strategy

Keep 1-2 active encoders, reduce FPS/bitrate for visible-but-secondary panes, suspend hidden panes, avoid Svelte frame state, prefer workers for parsing/health, and retain low-latency MediaCodec settings.

## 22. Incremental Migration Strategy

1. Keep existing embedded JS assets as the production path.
2. Build the Svelte frontend with `npm run build` inside `frontend/`.
3. Gradle copies `frontend/dist` to `app/src/main/assets/web`.
4. Move one browser subsystem at a time from `assets/web/js/main.js` to framework-independent TypeScript.
5. Replace direct server callbacks with `DisplaySessionRegistry` session methods.
6. Enable worker-owned frame synchronization and decoder lifecycle.
7. Move diagnostics overlay from optional debug UI to a feature flag.
