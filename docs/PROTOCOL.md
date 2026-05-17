# LumaFader ↔ Bitwig MIDI Protocol (v1)

All controller → host traffic is **USB MIDI only** (one configurable channel, default 1).

**Gesture bindings**, **USER_CC_GRID**, and MIDI channel/sensitivity are configurable in `settings.json`. **Fader CC banks** (20–35) and **ACTION_CC** numbers (40–47, 50–53, 60–62) are fixed to match the Bitwig extension and are always reset to defaults on load.

## Controller → Bitwig

### Faders (absolute CC) — 4 CCs

| CCs | Default | Behavior |
|-----|---------|----------|
| 20–23 (`FADER_CC`) | fixed | Absolute position **0–127** when the fader moves |

CC **20–35** are applied in the Bitwig extension via `onMidi` (Bitwig’s controller “relative scaling” does not apply to `parameter.set()`). On **overlay** change, the extension **ramps** each fader from the current host value toward the physical position (≤4 CC per message). After **fine-modifier release**, firmware maps fader motion as a delta from the release anchor onto the fine-tuned value (relative pickup); the extension does not arm takeover on fine release. `ACTION_CC.fine_modifier` (53): 127 while button 1 held, 0 on release. Firmware only sends CC when the fader moves. LEDs show state via SysEx.

### Buttons

No per-button MIDI. Buttons are scanned locally for gestures and overlays.

### Focus overlay (button 4) — alternate fader CCs

| Bank | CCs | Focus | Four-Track |
|------|-----|-------|------------|
| `FADER_CC` | 20–23 | Remotes 1–4 (default) | Send levels on visible tracks |
| `FADER_CC_OVERLAY` | 24–27 | Remotes 5–8 while button 4 held | Track volume |
| `FADER_CC_SENDS` | 28–31 | Sends 1–4 on cursor track while button 3 held | Track pan |
| `FADER_CC_UTILITY` | 32–35 | Utility while button 2 held: last-touched, reserved, pan, volume | Same CCs; extension keeps send layer on btn 2 hold |
| `BINDINGS.overlay_1` | hold, button 4 | Remotes 5–8 |
| `BINDINGS.overlay_2` | hold, button 3 | Sends 1–4 |
| `BINDINGS.overlay_3` | hold, button 2 | Utility layer |
| `ACTION_CC.overlay_1` / `overlay_2` / `overlay_3` | 50 / 51 / 52 | **127 while held / 0 on release** — LED layer hint only (no fader motion) |

Fader CC bank switches locally on hold; absolute CCs are sent only when a fader moves.

### Fine control (local only)

| Setting | Default | Behavior |
|---------|---------|----------|
| `FINE_MODIFIER_BUTTON` | 0 | Button 1 (bottom) held: creep absolute CC toward physical in small steps (works with any overlay) |
| `FINE_SCALE` | 0.25 | Smaller = finer steps (`max CC per tick ≈ 1/scale`, default 4); local only |
| `ACTION_CC.fine_modifier` | 53 | **127 while fine held / 0 on release** — disarms takeover on press; release uses firmware pickup |

### Gestures (planned, not sent yet)

**Navigation chords** (firmware `ACTION_CC` pulse 127→0): hold button A, **quick-tap** button B (fires on B’s release if the tap was &lt;250 ms and A is still held; holding B down does not page). Examples: `nav_next_track` hold 4 tap 1 (CC 40), `nav_prev_track` hold 1 tap 4 (CC 41). At limit, SysEx `nav_reject_edge` flashes. After nav, fader MIDI is suppressed until each fader moves ≥2 CC (LEDs still update via SysEx).

**Current outbound MIDI:** up to 4 absolute fader CCs (bank 20–23 or 24–27 depending on button 4).

## Bitwig → Controller (SysEx)

Framing: standard `F0` … `F7` (42 bytes total). The extension sends full framing. Firmware uses `in_buf_size=64` (default adafruit_midi buffer is only 30 bytes and cannot receive this message). Reassemble `manufacturer_id` + `data` from `SystemExclusive` before `parse_sysex()`.

```
7D 01 01  <msg_type>  <payload...>
```

| Byte | Meaning |
|------|---------|
| `7D` | Manufacturer ID (non-commercial) |
| `01` | LumaFader Bitwig device ID |
| `01` | Protocol version |

### `0x10` Visible state (36 bytes)

| Offset | Field |
|--------|--------|
| 0 | mode_id |
| 1 | overlay_id |
| 2 | remote_scope |
| 3 + 5×i | fader[i]: mode, value, R, G, B |
| 23 + 3×i | button[i]: R, G, B |
| 35 | nav_reject_edge (0–4) |

Fader modes: `0` unipolar, `1` bipolar (symmetric), `2` dim (ignore motion), `3` standby (dim LED, motion allowed), `4` pan (green below / red above center from host value), `5` rainbow (firmware animates last-touched utility fader; host sends level only). Pan re-center deadzone (±4 CC at 64) is applied in the Bitwig extension when both host and incoming MIDI are near center—not in LED rendering.

Fader/button RGB bytes are **0–127 per channel**; firmware scales to 0–255 for NeoPixels.

**Mode indicator** (NeoPixel index 68, between faders B and C): solid color from `mode_id` — focus blue, four-track green, user red.

**Button LEDs** (pixels 0–3, bottom = button 1): firmware-local while held — fine off-white, button 2 red (utility), button 3 green (sends), button 4 blue (remotes 5–8). SysEx `button_color` is unused for now.

### `0x11` Mode change — 1 byte mode_id

### `0x12` Scope change — 1 byte remote_scope
