# LumaFader ↔ Bitwig protocol (v1)

USB MIDI between the CircuitPython firmware and the Bitwig extension (built against **Bitwig Studio 5.3.13**). Settings editing over USB serial uses a separate **Web Serial** protocol (`serial_config.py`, protocol version **2**) — see [web_config.html](web_config.html).

**Source of truth:** `firmware/src/constants.py`, `firmware/src/settings.py`, `extension/.../SysexProtocol.java`.

---

## Modes

| `mode_id` | Name | Indicator LED | Bitwig extension | Fader MIDI |
|-----------|------|---------------|------------------|------------|
| `0` | Focus | Blue | Maps remotes, sends, utility on one cursor track | CC banks 20–35 |
| `1` | Four-Track | Green | Four visible mixer tracks per page | CC banks 20–31 (utility bank unused in v1) |
| `2` | User | Red | Syncs mode only; does **not** map User faders | `USER_CC_GRID` on channels 1–4 |

**Mode ownership:** the **firmware** applies mode changes from double-tap bindings and pulses `ACTION_CC` 60–62. The extension listens and calls `switchMode`, then sends SysEx `0x11` so the device LED state stays aligned. Bitwig is not required for User mode.

---

## What is configurable

| Item | In `settings.json` | Notes |
|------|-------------------|--------|
| `MIDI_CHANNEL` | Yes | Default 1 (host channel 1) |
| `BINDINGS` | Yes | Double-tap and chord → action names |
| `FOCUS_FADER_LAYERS` | Yes | Which button activates each Focus fader view |
| `FOUR_TRACK_FADER_LAYERS` | Yes | Same for Four-Track |
| `USER_CC_GRID`, colors, `USER_BANK_SWITCH_PAGE` | Yes | User mode only |
| `FINE_MODIFIER_BUTTON`, `FINE_SCALE`, `DELTA_SCALE` | Yes | Fine modifier behavior |
| `FADER_CC` … `FADER_CC_UTILITY`, `ACTION_CC` | **No** | Reset to defaults on every load (extension contract) |

Legacy keys (`workspace_*`, shared `overlay_*` bindings, `focus_overlay_*`) are migrated on load; see `settings.py`.

---

## Fader CC banks (fixed)

Four physical faders (A–D). Each bank is four absolute CCs, **0–127**, sent only when the fader moves (or during fine creep).

| Bank key | CCs | Focus (default mapping) | Four-Track (default mapping) |
|----------|-----|-------------------------|------------------------------|
| `FADER_CC` | 20–23 | Remotes 1–4 | Send levels on visible tracks |
| `FADER_CC_OVERLAY` | 24–27 | Remotes 5–8 | Track volume |
| `FADER_CC_SENDS` | 28–31 | Sends 1–4 on cursor track | Track pan |
| `FADER_CC_UTILITY` | 32–35 | Last-touched, reserved, pan, volume | **Not handled by extension in v1** |

The extension applies values in `onMidi` by **CC number**, not only by current LED layer. In Four-Track mode, CC **32–35** are ignored.

---

## Fader layers (hold → bank)

Hold assignments are in `FOCUS_FADER_LAYERS` / `FOUR_TRACK_FADER_LAYERS` (web config or JSON). Each layer is triggered by `"default"` or a button index **0–3** (bottom = 0, top = 3). The fine-modifier button cannot be assigned.

When multiple holds are active, the active layer is the highest-priority non-default layer:

| Mode | Priority (highest wins) |
|------|-------------------------|
| Focus | utility → sends → remotes_5_8 → remotes_1_4 |
| Four-Track | utility → pan → volume → sends |

While a non-default layer is active, the firmware also drives the matching **`overlay_*` ACTION_CC** held (127) / released (0) so the extension updates LEDs and takeover.

### Default layer → button map

| Focus layer | Default trigger | CC bank |
|-------------|-----------------|---------|
| `remotes_1_4` | default | 20–23 |
| `remotes_5_8` | button 4 (top) | 24–27 |
| `sends` | button 3 | 28–31 |
| `utility` | button 2 | 32–35 |

| Four-Track layer | Default trigger | CC bank |
|------------------|-----------------|---------|
| `sends` | default | 20–23 |
| `volume` | button 4 | 24–27 |
| `pan` | button 3 | 28–31 |
| `utility` | button 2 | 32–35 (extension ignores) |

Overlay hold uses **`OVERLAY_HOLD_DELAY_S`** (~80 ms) so double-tap mode changes still work.

---

## ACTION_CC reference (fixed)

All on the configured MIDI channel. Navigation and mode actions use **pulse** (127 then 0). Overlays and fine use **held** (127 while active, 0 on release).

| CC | Key | Delivery | Focus | Four-Track | User |
|----|-----|----------|-------|------------|------|
| 40 | `nav_next_track` | pulse | Next track | — | — |
| 41 | `nav_prev_track` | pulse | Previous track | — | — |
| 42 | `nav_next_device` | pulse | Next device in chain | — | — |
| 43 | `nav_prev_device` | pulse | Previous device / track remotes | — | — |
| 44 | `nav_next_track_page` | pulse | — | Next page (4 tracks) | — |
| 45 | `nav_prev_track_page` | pulse | — | Previous page | — |
| 46 | `nav_next_send` | pulse | — | Next send bus | — |
| 47 | `nav_prev_send` | pulse | — | Previous send bus | — |
| 50 | `overlay_1` | held | Remotes 5–8 layer hint | Volume layer hint | — |
| 51 | `overlay_2` | held | Sends layer hint | Pan layer hint | — |
| 52 | `overlay_3` | held | Utility layer hint | (LED stays on sends) | — |
| 53 | `fine_modifier` | held | Fine steps; disarms takeover on press | Same | Same (firmware only) |
| 60 | `mode_focus` | pulse | Enter Focus | Sync | Sync only |
| 61 | `mode_four_track` | pulse | Enter Four-Track | Sync | Sync only |
| 62 | `mode_user` | pulse | Enter User | Sync | Sync only |

User-mode navigation uses separate binding names (`nav_user_bank_*`, `nav_user_page_*`) with the same chord rules; they do not use CC 40–47.

---

## Gestures (`BINDINGS`)

| Type | Rule |
|------|------|
| `double_tap` | Two presses on one button within **`DOUBLE_PRESS_TIME`** (~300 ms) |
| `chord` | Hold button A; **quick-tap** button B — fires on B’s **release** if B was down ≤ **`CHORD_TAP_MAX_S`** (250 ms), A still held, and B was not a long hold (≥500 ms) |

Firmware filters navigation actions by current mode (Focus vs Four-Track). User mode handles only user-nav binding names.

Default binding layout is in `settings.py` `DEFAULTS["BINDINGS"]`; shipped `settings.json` may differ (e.g. which button double-taps Focus).

---

## Fine control

| Setting | Default | Behavior |
|---------|---------|----------|
| `FINE_MODIFIER_BUTTON` | 0 (button 1) | While held: creep absolute CC in small steps |
| `FINE_SCALE` | 0.25 | Smaller → finer steps (~4 CC max per tick) |
| `ACTION_CC.fine_modifier` | 53 | 127 held / 0 release — extension disarms takeover on press |

After fine **release**, firmware maps motion as delta from the release anchor until aligned (~2 CC). The extension does **not** arm takeover on fine release (that would fight firmware pickup).

---

## Pickup and suppression

| Situation | Threshold | Behavior |
|-----------|-----------|----------|
| After **nav chord** | `NAV_PICKUP_SYNC_THRESHOLD_CC` (2) | No fader CC until physical moves ≥2 from anchor; SysEx LEDs still update |
| After **fine release** | `FINE_PICKUP_SYNC_THRESHOLD_CC` (2) | Relative pickup to last sent value |
| **User mode** | `CC_THRESHOLD` (2) | Classic cross-value pickup per (CC, channel); dim LED until picked up |
| **FADER_MODE_DIM** | — | Fader motion ignored (no CC) |

---

## User mode MIDI (no Bitwig mapping)

- Incoming **SysEx is ignored** while `mode_id == 2`.
- Outbound: absolute CC from `USER_CC_GRID`; MIDI channel = bank number (1–4).
- Views: 0 = default; hold buttons 4 / 3 / 2 for views 1–3 (fixed; not in `FOCUS_FADER_LAYERS`).
- Enter state: bank **4**, page **1**, view **0** (channel 4).

---

## Extension behavior (Focus / Four-Track)

- Fader CCs are applied in `onMidi` with `parameter.set()` (not host relative encoding).
- On overlay **release** or after bank change, **FaderTakeover** ramps host values toward the physical fader (≤4 CC per step).
- **Pan:** extension applies a ±4 CC deadzone at center (64) when host and incoming CC are both near center; firmware renders green below / red above center for `FADER_MODE_PAN`.
- **Sends:** undefined project send → `FADER_MODE_DIM`; defined but not routed → `FADER_MODE_STANDBY` (dim, first touch enables).

---

## Controller → Bitwig (summary)

| Traffic | When |
|---------|------|
| Absolute CC 20–35 | Focus / Four-Track fader motion |
| ACTION_CC 40–47, 60–62 | Pulses (nav, mode) |
| ACTION_CC 50–53 | Held (overlays, fine) |
| User CC grid | User mode only, per-bank channel |

No per-button note MIDI in Bitwig modes. (Stock Cherry relative CC helpers exist in `midi.py` but are unused here.)

---

## Bitwig → Controller (SysEx)

**Framing:** `F0` … `F7` (42 bytes on the wire). Firmware `MidiManager` uses `in_buf_size=64` (default adafruit_midi buffer is 30 bytes and cannot receive visible state). Reassemble `manufacturer_id` + `data` from `SystemExclusive` before `parse_sysex()`.

```
7D 01 01  <msg_type>  <payload…>
```

| Byte | Meaning |
|------|---------|
| `7D` | Manufacturer ID (non-commercial) |
| `01` | LumaFader Bitwig device ID |
| `01` | Protocol version |

### `0x10` Visible state — 36-byte payload

Sent periodically and on changes (~200 ms baseline + scheduled updates). **Not processed in User mode.**

| Offset | Field |
|--------|--------|
| 0 | `mode_id` (0 Focus, 1 Four-Track, 2 User) |
| 1 | `overlay_id` — extension hint: 0 default, 1 overlay_1, 2 overlay_2, 3 utility (Focus) |
| 2 | `remote_scope` — 0 device remotes, 1 track remotes |
| 3 + 5×i | Fader i: `mode`, `value`, R, G, B |
| 23 + 3×i | Button i: R, G, B (unused for painting; firmware uses local hold colors) |
| 35 | `nav_reject_edge` — 0 none, 1 left, 2 right, 3 top, 4 bottom |

**Fader modes**

| Value | Name | Notes |
|-------|------|--------|
| 0 | unipolar | Standard level strip |
| 1 | bipolar | Symmetric from center |
| 2 | dim | Ignore motion; LED off/dim |
| 3 | standby | Dim LED; motion allowed (unused send) |
| 4 | pan | Green below center CC, red above |
| 5 | rainbow | Firmware animates utility “last touched”; host sends level only |

RGB in SysEx is **0–127 per channel**; firmware scales to 0–255 for NeoPixels.

**Mode indicator** (pixel 68): blue / green / red from `mode_id`.

**Button LEDs** (pixels 0–3): firmware-local while held — fine off-white, utility red, sends green, remotes-5–8 blue.

**Nav reject:** non-zero `nav_reject_edge` starts a ~150 ms flash (`NAV_REJECT_FLASH_S`).

### `0x11` Mode change — 1 byte `mode_id`

Sent by the extension when it switches mode (including after receiving firmware mode pulses). Updates firmware `visible_state.mode_id`.

### `0x12` Scope change — 1 byte `remote_scope`

Parsed by firmware for compatibility; the extension does **not** send this message. `remote_scope` is included in every `0x10` update instead.

---

## Web Serial settings (v2)

Line-oriented, newline-terminated:

- Host → device: `CMD:<command>\n`
- Device → host: `RSP:<json>\n`

| Command | Purpose |
|---------|---------|
| `PING` | Keepalive |
| `GET_SETTINGS` | JSON settings (migrations applied) |
| `GET_CAPS` | `protocol_version: 2`, `bitwig_mode`, `gesture_bindings` |
| `GET_STATUS` | Live mode, overlays, buttons, sliders |
| `SET_SETTINGS\|<json>` | Write `settings.json` (restart to apply) |
| `SET_CONFIG_MODE\|0\|1` | Optional config session flag |
| `START_LEARN\|0-3` / `STOP_LEARN` | MIDI learn (Cherry-compatible) |

Disconnect Web Serial before `python3 scripts/deploy.py` — only one client may use the USB serial port ([DEPLOY.md](DEPLOY.md)).
