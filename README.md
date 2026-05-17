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

Optional: `USB_DRIVE_AT_BOOT: true` in `settings.json` may expose **LUMAFADER** on normal plug-in for drag-and-drop; mpremote still works if it does not.

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
- **Extension:** **Focus workspace** implemented (see below). Four-track and user workspaces are not implemented in the extension yet. Build with `extension/build.zsh`; install `extension/build/LumaFader.bwextension` into Bitwig’s Extensions folder.

## Focus workspace

Focus is the default Bitwig mode for detailed editing on **one track at a time**. The extension follows Bitwig’s **cursor track** and **cursor device**; faders and navigation move that selection. The workspace indicator LED (between faders B and C) is **blue**.

Double-tap **button 1** (bottom) to enter Focus from another workspace (when those are wired up). Default bindings are in `src/settings.json`; only the gesture→CC mapping is configurable there — parameter meaning is fixed in the extension.

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

Utility fader A (last-touched) uses a rainbow animation when a target exists; B stays inactive.

### Install / reload

```bash
cd extension && ./build.zsh
cp build/LumaFader.bwextension ~/Documents/Bitwig\ Studio/Extensions/
```

Reload the extension in Bitwig after copying. Firmware changes: `python3 scripts/deploy.py`.

## Known limitations

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
