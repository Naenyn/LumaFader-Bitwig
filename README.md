# LumaFader-Bitwig

Firmware and Bitwig Studio controller extension for the [DJBB LumaFader](https://www.djbajablast.com/post/lumafader68).

Same repo layout as [Midi-Slider-Cherry](https://github.com/derrickthomin/Midi-Slider-Cherry):

```
src/              CircuitPython source
scripts/          deploy.py, initializer.py
combineallcode.py Merge src for review
docs/             Protocol + deploy notes
uf2/              CircuitPython installer (.uf2)
```

## Deploying firmware (read this)

**Pico BOOT** (small button on the **PCB**, not used during performance) + USB → **`RPI-RP2`** in `/Volumes`. That is the chip’s UF2 loader — use it only to drag **`uf2/*.uf2`**, not `.py` files.

**Normal plug-in** (do not hold Pico BOOT) → CircuitPython runs → deploy code with:

```bash
python3 -m pip install mpremote   # once
python3 scripts/deploy.py
```

No `/Volumes` mount required. See [docs/DEPLOY.md](docs/DEPLOY.md) for the full picture.

**Edit settings on the device:** hold **all four front buttons** while plugging in USB → red blink ×3, then rainbow → **LUMAFADER** mounts. Edit `settings.json`, eject, then **hard-reset** (unplug/replug, no buttons) for normal mode. See [docs/DEPLOY.md](docs/DEPLOY.md). Routine deploy: `python3 scripts/deploy.py`.

### First-time install

1. Hold **Pico BOOT**, plug USB → copy `uf2/adafruit-circuitpython-raspberry_pi_pico-en_US-9.2.8.uf2` to **RPI-RP2** (9.x needed for short MIDI port name **LumaFader** in ShowMIDI etc.).
2. Release BOOT, replug, run `python3 scripts/deploy.py`.
3. Install **CircuitPython 9.x** libraries (required after UF2 upgrade; 8.x `.mpy` files will crash silently):

   ```bash
   ./scripts/install_libs.sh
   ```

   Or copy from the [9.x bundle](https://github.com/adafruit/Adafruit_CircuitPython_Bundle/releases) manually.

## Status

- **Firmware:** Bitwig mode — absolute fader CCs, overlay banks, gesture/action CCs, SysEx LEDs, USB MIDI only.
- **Extension:** **Focus** (blue), **Four-Track** (green), and **User** (red, DAW-agnostic CC) are implemented. Build with `extension/build.zsh`; install `extension/build/LumaFader.bwextension` into Bitwig’s Extensions folder.

## Workspaces (overview)

| Button (double-tap) | Workspace | Indicator | Role |
|---------------------|-----------|-----------|------|
| 4 (top) | Focus | Blue | One track + device chain; follows Bitwig selection |
| 3 | Four-Track | Green | Four mixer tracks per page; does not follow selection after enter |
| 2 | User | Red | User-defined CC grid; firmware-only LEDs and pickup |

Workspace switches use **ACTION_CC** pulses on CC **60–62** (see [docs/PROTOCOL.md](docs/PROTOCOL.md)). Overlay holds use CC **50–52**; they engage after a short press (~80 ms) so the same buttons can still double-tap for workspace changes.

### Navigation chords (both workspaces)

Chords fire on a **quick tap** of the second button: the tap must release within **250 ms**, the hold button must still be down, and a long hold (≥500 ms) on the tap button does not count. This lets you hold button 1 (fine modifier) or hold an overlay button without accidentally paging.

Example: holding button 4 for volume and pressing button 1 for fine control does **not** change the Four-Track page; a deliberate tap of button 1 while holding 4 does.

## Focus workspace

Focus is the default Bitwig mode for detailed editing on **one track at a time**. The extension follows Bitwig’s **cursor track** and **cursor device**; faders and navigation move that selection. The workspace indicator LED (between faders B and C) is **blue**.

**Workspace double-taps** (see `src/settings.json`):

| Button | Workspace |
|--------|-----------|
| 4 (top) | Focus (blue indicator) |
| 3 | Four-Track (green) |
| 2 | User (red, not implemented yet) |

Default bindings are in `src/settings.json`; only the gesture→CC mapping is configurable there — parameter meaning is fixed in the extension.

### Fader layers

Four physical faders (A–D). Hold a button to switch which CC bank the faders send; release to return to remotes 1–4.

| Hold | Button | Faders control |
|------|--------|----------------|
| (default) | — | Remotes **1–4** on the active remote target (device or track) |
| Button 4 (top) | `overlay_1` | Remotes **5–8** |
| Button 3 | `overlay_2` | Sends **1–4** on the selected track |
| Button 2 | `overlay_3` | **Utility:** A = last-touched parameter, B = reserved, C = pan, D = volume |

**Fine control:** hold **button 1** for smaller steps (firmware scales motion; extension disarms takeover while fine is held). After fine release, motion is relative to the value you tuned, not a jump back to the fader’s old position.

On overlay change, the extension **ramps** each fader from the current host value toward the physical position (no sudden parameter jumps). After **track or device navigation**, firmware suppresses fader CC until each fader moves at least ~2 CC from where it was when you chorded (nav pickup); LEDs still update from Bitwig.

CC numbers and SysEx layout: [docs/PROTOCOL.md](docs/PROTOCOL.md).

### Remote target chain (device ↔ track)

Remotes always bind to whichever target is active:

- **Device remotes** — the selected device in the chain (default).
- **Track remotes** — the track’s remote controls page (left edge of the chain).

**Device navigation** (inner button pair — default chords):

| Chord | Action |
|-------|--------|
| Hold button 3, tap button 2 | Next device in chain |
| Hold button 2, tap button 3 | Previous device / into track remotes |

Rules:

- At the **first device**, previous device steps into **track remotes** (not an error flash).
- On **track remotes**, previous device is invalid → **left edge** LED flash; next device goes to the **first device** on the track.
- At the **last device**, next device is invalid → **right edge** flash.
- No wrapping.

**Track navigation** (outer button pair):

| Chord | Action |
|-------|--------|
| Hold button 4, tap button 1 | Next track |
| Hold button 1, tap button 4 | Previous track |

Moves one track in the flat track bank (main + FX, follows arranger). No wrapping. At the list top/bottom, navigation flashes the **top** or **bottom** edge.

After track change:

- If you were on **track remotes**, you stay on track remotes on the new track.
- If you were on a **device**, the extension selects the **first device** on the new track.

SysEx `remote_scope` tells the firmware whether LEDs should reflect **device** (0) or **track** (1) remotes.

### LED feedback

Bitwig sends **visible state** SysEx (`0x10`): per-fader mode, value, and color; `remote_scope`; optional **nav reject edge** (left/right for device limits, top/bottom for track limits). Firmware paints the four fader LEDs and can flash a column on reject.

Send overlay behavior:

- **Missing** send bus (project has fewer than four sends): LED off, fader ignored.
- **Unused** send (bus exists, track not routed): dim LED; first touch enables the send and sets level.

Utility fader A (last-touched) uses a firmware rainbow animation (random effect each time button 2 is held); B stays inactive.

### Install / reload

```bash
cd extension && ./build.zsh
cp build/LumaFader.bwextension ~/Documents/Bitwig\ Studio/Extensions/
```

Reload the extension in Bitwig after copying. Firmware changes: `python3 scripts/deploy.py`.

## Four-Track workspace (mode 2)

Multi-track mixing on **four visible mixer tracks at a time**. The viewport does **not** follow Bitwig’s selected track after you enter — you can click another track in the DAW while the hardware stays on its current page.

**On enter:** the page is aligned so the currently selected track lies on the active page (tracks 1–4, 5–8, … in the **visible** track list). Partial last pages leave unused faders **off**.

### Fader layers

| Hold | Button | Faders control | CC bank |
|------|--------|----------------|---------|
| (default) | — | **Send** *N* on slots 1–4 (shared send index) | 20–23 |
| Button 4 | `overlay_1` | **Volume** on slots 1–4 | 24–27 |
| Button 3 | `overlay_2` | **Pan** on slots 1–4 (bipolar LEDs) | 28–31 |
| Button 2 | `overlay_3` | Unused in v1 | — |

**Fine control:** button 1 works as in Focus (smaller steps while held).

Send overlay behavior matches Focus: missing send bus → LED off; unused send on a track → dim LED, first touch enables routing.

### LED colors

Fader LEDs use **send-bus colors** on the send page (same as Focus sends overlay) and **track color** for volume and pan. Inactive slots on a partial page are off.

### Navigation

| Chord | Action |
|-------|--------|
| Hold button 4, tap button 1 | Next **page** of four tracks — down / higher track numbers (bottom edge flash at end) |
| Hold button 1, tap button 4 | Previous **page** — up / lower track numbers (top edge flash at start) |
| Hold button 3, tap button 2 | Next **send** bus (right edge flash at limit) |
| Hold button 2, tap button 3 | Previous **send** (left edge flash at limit) |

### Track list and paging

Visible tracks = `exists()` + `isActivated()` in a 256-slot flat track bank (collapsed/hidden mixer rows excluded). Pages are quantized to groups of four (1–4, 5–8, … never 2–5).

The extension maps logical slots to bank indices; Bitwig’s mixer **highlight** is always four **consecutive** arranger tracks at the page scroll position.

### Mixer highlight (bounding box)

While in Four-Track, Bitwig draws a track-window box around four consecutive tracks (`setShouldShowClipLauncherFeedback` — **indication only**, no clip launching). The box tracks the controller page, not UI selection, so you can see which block the hardware is addressing when the arranger selection differs.

Implementation notes: `FourTrackViewport` manages the visible list and paging; a 4-track `TrackBank` scrolls via API 6 `scrollPosition` / `scrollIntoView` (not deprecated `scrollToChannel`).

### Deploy after changes

```bash
cd extension && ./build.zsh
cp build/LumaFader.bwextension ~/Documents/Bitwig\ Studio/Extensions/
python3 scripts/deploy.py   # firmware
```

Reload the extension in Bitwig after copying the `.bwextension`.

## User workspace (mode 3)

DAW-agnostic **absolute MIDI CC** control. **Workspace changes are handled on the firmware** (red/green/blue indicator, user CC grid) — Bitwig does not need to be running. If the LumaFader extension is loaded, it stays in sync via the same workspace action CCs and SysEx, but it does **not** map your User-mode fader CCs. Configure **Generic MIDI** (or any host) to receive CCs on channels **1–4**.

### Grid

| Dimension | Count | Notes |
|-----------|-------|--------|
| Banks | 4 | Bank **N** → MIDI **channel N** (bank 4 = ch 4, default) |
| Pages per bank | 4 | Page **1** = bottom button LED |
| Views per page | 4 | View 0 default; hold buttons **4 / 3 / 2** for views 1–3 |
| Faders | 4 | CC index `(page-1)×16 + view×4 + fader` → **0–63** per channel |

Default CC layout is generated in `settings.py` (`USER_CC_GRID`). Override in `settings.json`.

### Navigation (chords — quick tap on release)

| Chord | Action |
|-------|--------|
| Hold **4**, tap **1** | Bank down (4→3→2→1) — flash that bank’s button LED |
| Hold **1**, tap **4** | Bank up (1→2→3→4) |
| Hold **2**, tap **3** | Page up (1→2→3→4) — flash that page’s button LED |
| Hold **3**, tap **2** | Page down |

**Enter User mode:** bank **4**, page **1**, view **0**, channel **4**.

`USER_BANK_SWITCH_PAGE`: `"remember"` (default) keeps each bank’s last page; `"reset"` forces page 1 on bank change.

### Pickup (from original LumaFader / Cherry firmware)

Firmware remembers the last CC value sent per **(CC, channel)**. Faders do not send until the physical control **crosses** that value (with edge handling at 0/127). Unpicked slots show **dim** faders on the hardware.

### Fine modifier

Hold **button 1** for smaller steps (same as other workspaces).

### LEDs (firmware only)

- Fader color: **bank** base hue × **page** shade (`USER_BANK_COLORS`, `USER_PAGE_SHADES` in settings).
- Nav: brief flash on the **button LED** for the bank or page you landed on.
- Limits: fader/button edge flash (same style as Focus/Four-Track nav reject).

### Not supported

- Simultaneous bank + page navigation gestures.
- Bitwig-driven fader colors or parameter feedback in User mode.
- Aftertouch (CC only).

## Known limitations

### Four-Track vs arranger layout

If the visible track list skips hidden or collapsed rows, fader slots may not line up one-to-one with the four consecutive tracks in Bitwig’s highlight window. Paging and control still use the logical visible list; the highlight shows the arranger scroll window.

### Track remotes in the device panel

In Focus workspace, navigating **left** from the first device on a track enters **track remote scope**: faders 1–4 (and overlay 5–8) bind to that track’s remote controls page, the arranger focuses the track, and SysEx `remote_scope` reports track (1) for LED semantics.

**The track remotes row in Bitwig’s device panel does not expand automatically.** That is a Bitwig Controller API limitation, not a firmware bug:

- `Device.isRemoteControlsSectionVisible()` can toggle the remote strip for a **device** in the chain; there is no equivalent on `Track`.
- `Track.createCursorRemoteControlsPage()` exposes parameters for mapping/control only, not panel visibility.
- The device cursor stays on the selected plugin when entering track remotes; `selectChannel` / `selectInEditor` focus the track but do not move the cursor to a track-header “device.”
- No hidden Bitwig **Application** actions expose track-remote panel visibility either.

**Accepted behavior for now:** hardware controls track remotes correctly; open the track remotes strip in the device panel manually if you want it visible on screen.

## Reference (read-only)

- `../Midi-Slider-Cherry` — original firmware
- `../DrivenByMoss` — Bitwig extension patterns
