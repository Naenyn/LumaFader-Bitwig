# LumaFader ↔ Bitwig MIDI Protocol (v1)

## Controller → Bitwig (channel from `settings.json`, default 1)

| Message | Default | Description |
|---------|---------|-------------|
| Relative CC | 20–23 | Fader A–D deltas (65 = inc, 63 = dec per step) |
| Note on/off | 60–63 | Button 1–4 press/release |
| CC | 100 | Fine modifier (127 = active) |
| CC | 110–111 | Gesture ID + parameter |

### Gesture IDs (`CC 110`)

| ID | Meaning | Param |
|----|---------|-------|
| 1 | Mode switch | 0 Focus, 1 Four-Track, 2 User |
| 2 | Remote scope toggle | unused |
| 3–10 | Navigation | unused |
| 11 | Overlay hold | 1–3 while held |

## Bitwig → Controller (SysEx)

Framing: `F0` … `F7` (handled by `adafruit_midi`).

Payload after framing:

```
7D 01 01  <msg_type>  <payload...>
│  │  │       │
│  │  │       └─ message type
│  │  └─ protocol version
│  └─ device ID (LumaFader Bitwig)
└─ manufacturer ID (non-commercial)
```

### `0x10` Visible state update (36 bytes)

| Offset | Field |
|--------|--------|
| 0 | mode_id |
| 1 | overlay_id |
| 2 | remote_scope |
| 3 + 5×i | fader[i]: mode, value, R, G, B |
| 23 + 3×i | button[i]: R, G, B |
| 35 | nav_reject_edge (0 none, 1 left, 2 right, 3 top, 4 bottom) |

Fader modes: `0` unipolar, `1` bipolar, `2` dim/unavailable (ignore fader motion).

### `0x11` Mode change (1 byte)

Single byte: new mode_id.

### `0x12` Scope change (1 byte)

Single byte: remote_scope.
