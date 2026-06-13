# Multiwindow Layout Transition Notes

## Purpose

This document captures the current state of the mirroring layout work so a future thread can continue without rediscovering the same context.

It focuses on:

- touch and layout fixes already completed
- popup and split resize behavior that was debugged
- the current UX direction for `single`, `split`, and `popup`
- the recommended next implementation steps

## What Was Fixed

### 1. Touch mapping was separated from raw stream sizing

The main source of confusion was mixing these concepts:

- UI viewport size
- decoder stream size
- backend touch injection size

Current intended model:

- `viewport.width/height`: frontend layout size
- `streamWidth/streamHeight`: actual decoded stream metadata
- `mappedWidth/mappedHeight`: explicit backend touch injection basis

This prevented the old behavior where touch math drifted when the visual pane size and the decoder size were different.

### 2. WebCodec touch alignment was fixed

`webcodec` path now behaves correctly for touch after the layout/touch changes.

### 3. JMuxer touch alignment in split was fixed

For `fill` mode, touch mapping now uses the pane viewport size instead of stale intrinsic media size.

This resolved the case where split resize looked correct visually but touch still targeted the old stream width.

### 4. Barrier release logic was simplified

The old barrier logic depended too much on temporary width/height matching.

Barrier release was changed to rely more on:

- committed viewport state
- fresh metadata/generation

instead of strict stale size comparisons.

### 5. Stale rebuilds were filtered on the backend

Older rebuild requests could finish late and overwrite the latest intended layout.

Backend rebuild coordination was tightened so stale requests can be skipped when a newer browser layout target already exists.

### 6. Popup resize now dispatches real layout updates again

Popup resize had two separate issues:

- UI popup size could diverge from stream-aligned size
- popup layout dispatch could be blocked by a split-only dispatch lock path

Both were addressed:

- popup geometry is normalized to stream-friendly aligned dimensions when committed
- popup layout dispatch is no longer blocked by split-only target guards

### 7. Popup resize post-processing was made more like split resize

After popup resize commit:

- layout flush runs
- keyframes are requested
- touch router state is reset

This makes popup resize behave more like splitbar resize from a synchronization standpoint.

## Current Expected Behavior

### Stable now

- `webcodec` touch
- `jmuxer` split touch
- popup resize dispatch path
- popup touch after resize should now follow the committed pane geometry

### Still not yet redesigned

The current `single -> split` UX/structure is still not ideal.

The low-level bug fixes improved transition stability, but the product behavior itself is still under reconsideration.

## Key Product Insight

`split` and `popup` should not be treated as completely separate concepts.

They are both forms of:

- keep `primary` visible
- add a `secondary` surface somewhere

The only real difference is how the secondary is presented:

- docked
- floating

## Agreed UX Direction

### Secondary-first model

Instead of thinking in terms of:

- `single`
- `split`
- `popup`

the UI should think in terms of:

- primary only
- secondary added somewhere

### Empty-secondary behavior

When no secondary app exists yet:

- do **not** immediately shrink the primary app
- keep the primary app full-screen
- only show a lightweight placeholder/slot for the future secondary area

This applies conceptually to both split and popup.

### Why this is preferred

- avoids damaging the primary layout before a secondary app is chosen
- matches the already intuitive popup mental model
- makes future left/right/top/bottom/popup placement easier to unify

## Agreed Interaction Direction

### App drag should reveal drop targets

Instead of forcing users to pick mode first, the better model is:

1. start dragging an app
2. show placement targets
3. drop onto a target
4. interpret the target as the secondary presentation choice

### Recommended targets

- left
- right
- top
- bottom
- center = popup

### Visual guidance

Previous drop zones felt too large and visually heavy.

Preferred direction:

- show only reasonably sized targets
- do not flood the whole screen with giant overlays
- use small, clear placement markers that are easy enough to hit

## How Mode Change Should Work After Secondary Exists

When a secondary app is already active, changing "mode" should really mean:

- move the same secondary app to another placement target

Examples:

- right dock -> bottom dock
- popup -> left dock
- top dock -> popup

This should reuse the same target model instead of introducing a separate mode-switch concept.

## How Primary/Secondary Swap Should Work

Swap should stay a dedicated explicit action.

It should:

- preserve the current placement style
- exchange primary and secondary roles

Example:

- current: primary map, secondary music, right-docked
- after swap: primary music, secondary map, right-docked

## Important Open UX Decision

### Secondary-empty split preparation

The team direction currently favors:

- primary stays visually full-screen
- a secondary target/slot is only implied or lightly represented
- actual split rebuild happens only when a secondary app is chosen

This means:

- `single -> split` should become a "prepare secondary placement" action
- not an immediate hard viewport split

## Side Drawer UX

Requested behavior:

- when the sidebar drawer is open
- clicking outside the drawer should close it

This was implemented via a lightweight scrim approach.

## Recommended Next Steps

### 1. Unify layout state around secondary placement

Move away from a strict split-vs-popup-first model.

Target conceptual state:

- primary only
- secondary placement active/inactive
- secondary placement target:
  - left
  - right
  - top
  - bottom
  - popup

### 2. Rework `single -> split`

Do not immediately resize the primary just because split was selected.

Instead:

- enter a secondary-placement-ready state
- keep primary visually full-size
- only commit real split geometry when a secondary app is dropped/selected

### 3. Reuse the same target UI for mode changes

If secondary already exists:

- show the same placement targets again
- moving between split/popup/left/right/top/bottom becomes the same operation

### 4. Keep swap separate

Do not overload placement targets with swap behavior.

Keep swap as a dedicated explicit control.

## Good Starting Point For The Next Thread

If continuing in a new conversation, start from this summary:

1. popup resize/touch/layout sync bugs were fixed
2. touch/layout sizing is now separated into layout size, stream size, and mapped injection size
3. the next big task is not another bugfix first, but restructuring the UX/state model
4. the intended direction is a unified secondary-placement model with drag targets:
   - left
   - right
   - top
   - bottom
   - popup
5. `single -> split` should become "prepare/add secondary" rather than immediate hard split
