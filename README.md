# LumaFader-Bitwig

Firmware and Bitwig Studio controller extension for the [DJBB LumaFader](https://www.djbajablast.com/post/lumafader68).

```
firmware/
  src/            CircuitPython source (deployed to the device)
  uf2/            CircuitPython OS images (local only — not in git)
extension/
  src/            Bitwig extension (.java → .bwextension)
scripts/          deploy.py, initializer.py, install_libs.sh
docs/             Protocol, deploy notes, web_config.html
combineallcode.py Merge firmware/src for review
```

Stock [Midi-Slider-Cherry](https://github.com/derrickthomin/Midi-Slider-Cherry) keeps a flat `src/` at repo root; this repo uses `firmware/src/` next to `extension/src/`.

## Development requirements

What you need on a dev machine to work on this repo. End-user setup (flash, deploy, install extension) is under [Deploying firmware](#deploying-firmware-read-this) and [Install / reload](#install--reload).

### Hardware

| Item | Notes |
|------|--------|
| **DJBB LumaFader** (or Pico + matching hardware) | USB data cable; test MIDI, serial, and LEDs on real hardware |
| **Pico BOOT** (PCB button) | Only for flashing CircuitPython `.uf2` — not used in normal play |

### Firmware (CircuitPython)

Firmware is **not compiled** — Python source in `firmware/src/` is copied to the device. You edit on the host and deploy.

| Requirement | Used for |
|-------------|----------|
| **Python 3** | `scripts/deploy.py`, `scripts/initializer.py` |
| **[mpremote](https://docs.circuitpython.org/en/latest/docs/mpremote.html)** | Routine deploy: `python3 -m pip install mpremote` |
| **`curl`** and **`unzip`** | `scripts/install_libs.sh` (downloads Adafruit 9.x bundle) |
| **Chrome or Edge** | `docs/web_config.html` (Web Serial settings editor) |
| **CircuitPython 9.2.x UF2** | One-time device runtime — see [UF2 files](#circuitpython-uf2-files-uf2) (not in git) |

Optional: `scripts/initializer.py` (interactive UF2 flash + volume copy) if you prefer drag-and-drop over `mpremote` for a full factory setup.

### Bitwig extension

The extension is **compiled** to a single `LumaFader.bwextension` (ZIP of JVM classes). Bitwig loads it from its Extensions folder.

| Requirement | Used for |
|-------------|----------|
| **Bitwig Studio** (installed) | Provides `bitwig.jar` API; runtime host for testing |
| **JDK 8+** with `javac` and `jar` on `PATH` | `extension/build.zsh` compiles with `--release 8` |
| **zsh** | `build.zsh` uses zsh-specific syntax — do not run with bash |
| **`BITWIG_APP_PATH` or `BITWIG_JAR`** (if non-default install) | Default: `/Applications/Bitwig Studio.app` on macOS |

Build:

```bash
cd extension && zsh build.zsh
```

Install the artifact:

| OS | Extensions folder |
|----|-------------------|
| macOS | `~/Documents/Bitwig Studio/Extensions/` |
| Linux | `~/Bitwig Studio/Extensions/` |
| Windows | `%USERPROFILE%\Documents\Bitwig Studio\Extensions\` |

Reload the extension in Bitwig after copying `extension/build/LumaFader.bwextension`.

**Built and tested against Bitwig Studio 5.3.13** (`bitwig.jar` from that install). Other 5.x versions may work if the Controller API is compatible; compile against the same major version you run.

### Repo-only (optional)

| Tool | Used for |
|------|----------|
| **Python 3** | `combineallcode.py` → `srccombined.txt` (review bundle; output is gitignored) |

Protocol and deploy details: [docs/PROTOCOL.md](docs/PROTOCOL.md), [docs/DEPLOY.md](docs/DEPLOY.md).

## CircuitPython UF2 files (`firmware/uf2/`)

The `firmware/uf2/` folder is **gitignored**. Download these binaries once and save them under `firmware/uf2/` (create the folder if needed). They are **not** built by this repo — they install the CircuitPython runtime on the Pico.

| Save as | Purpose | Download |
|---------|---------|----------|
| `adafruit-circuitpython-raspberry_pi_pico-en_US-9.2.8.uf2` | **Required** — CircuitPython 9.2.8 for Raspberry Pi Pico (short USB MIDI name, matches `install_libs.sh`) | [Direct .uf2](https://downloads.circuitpython.org/bin/raspberry_pi_pico/en_US/adafruit-circuitpython-raspberry_pi_pico-en_US-9.2.8.uf2) · [Board page](https://circuitpython.org/board/raspberry_pi_pico/) |
| `flash_nuke.uf2` | **Optional** — wipe flash before a clean install or when upgrading from very old firmware | [Adafruit: Resetting your Pico](https://learn.adafruit.com/resetting-your-pico-board) ([direct .uf2](https://cdn-learn.adafruit.com/downloads/flash_nuke.uf2)) |

Use **9.2.x** only. Older 8.x `.uf2` / library bundles are incompatible with this project.

## Deploying firmware (read this)

**Pico BOOT** (small button on the **PCB**, not used during performance) + USB → **`RPI-RP2`** in `/Volumes`. That is the chip’s UF2 loader — use it only to drag a file from **`firmware/uf2/`**, not `.py` files.

**Normal plug-in** (do not hold Pico BOOT) → CircuitPython runs → deploy code with:

```bash
python3 -m pip install mpremote   # once
python3 scripts/deploy.py
```

No `/Volumes` mount required. See [docs/DEPLOY.md](docs/DEPLOY.md) for the full picture.

**Web config:** open [`docs/web_config.html`](docs/web_config.html) in Chrome or Edge (Web Serial), connect, edit, save, then hard-reset the device. Disconnect (or close the tab) before `python3 scripts/deploy.py` — only one program can use the USB serial port at a time ([details](docs/DEPLOY.md#editing-settings-on-the-device)).

**Edit settings on the device:** hold **all four front buttons** while plugging in USB → red blink ×3, then rainbow → **LUMAFADER** mounts. Edit `settings.json`, eject, then **hard-reset** (unplug/replug, no buttons) for normal mode. See [docs/DEPLOY.md](docs/DEPLOY.md). Routine deploy: `python3 scripts/deploy.py`.

### First-time install

1. Download the UF2 files into `firmware/uf2/` (see table above).
2. Hold **Pico BOOT**, plug USB → drag `firmware/uf2/adafruit-circuitpython-raspberry_pi_pico-en_US-9.2.8.uf2` onto **RPI-RP2**.
3. Release BOOT, replug, run `python3 scripts/deploy.py`.
4. Install **CircuitPython 9.x** libraries (required after UF2 install; 8.x `.mpy` files will crash silently):

   ```bash
   ./scripts/install_libs.sh
   ```

   Or copy from the [9.x bundle](https://github.com/adafruit/Adafruit_CircuitPython_Bundle/releases) manually.

## Status

- **Firmware:** Bitwig mode — absolute fader CCs, overlay banks, gesture/action CCs, SysEx LEDs, USB MIDI only.
- **Extension:** **Focus** (blue), **Four-Track** (green), and **User** (red, DAW-agnostic CC) are implemented. Built against **Bitwig 5.3.13**. Build with `extension/build.zsh`; install `extension/build/LumaFader.bwextension` into Bitwig’s Extensions folder.

## Modes (overview)

| Button (double-tap) | Mode | Indicator | Role |
|---------------------|-----------|-----------|------|
| 4 (top) | Focus | Blue | One track + device chain; follows Bitwig selection |
| 3 | Four-Track | Green | Four mixer tracks per page; does not follow selection after enter |
| 2 | User | Red | User-defined CC grid; firmware-only LEDs and pickup |

Mode switches use **ACTION_CC** pulses on CC **60–62** (see [docs/PROTOCOL.md](docs/PROTOCOL.md)). Overlay holds use CC **50–52**; they engage after a short press (~80 ms) so the same buttons can still double-tap for mode changes.

### Navigation chords (both modes)

Chords fire on a **quick tap** of the second button: the tap must release within **250 ms**, the hold button must still be down, and a long hold (≥500 ms) on the tap button does not count. This lets you hold button 1 (fine modifier) or hold an overlay button without accidentally paging.

Example: holding button 4 for volume and pressing button 1 for fine control does **not** change the Four-Track page; a deliberate tap of button 1 while holding 4 does.

## Focus mode

Focus is the default Bitwig mode for detailed editing on **one track at a time**. The extension follows Bitwig’s **cursor track** and **cursor device**; faders and navigation move that selection. The mode indicator LED (between faders B and C) is **blue**.

**Mode double-taps** (see `firmware/src/settings.json`):

| Button | Mode |
|--------|-----------|
| 4 (top) | Focus (blue indicator) |
| 3 | Four-Track (green) |
| 2 | User (red, not implemented yet) |

Default bindings are in `firmware/src/settings.json`; only the gesture→CC mapping is configurable there — parameter meaning is fixed in the extension.

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
cd extension && zsh build.zsh   # needs Bitwig installed (API jar); use zsh, not bash
cp build/LumaFader.bwextension ~/Documents/Bitwig\ Studio/Extensions/
```

If the jar is not at `/Applications/Bitwig Studio.app`, set `BITWIG_APP_PATH` or `BITWIG_JAR` before running `build.zsh`.

Reload the extension in Bitwig after copying. Firmware changes: `python3 scripts/deploy.py`.

## Four-Track mode (mode 2)

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
cd extension && zsh build.zsh
cp build/LumaFader.bwextension ~/Documents/Bitwig\ Studio/Extensions/
python3 scripts/deploy.py   # firmware
```

Reload the extension in Bitwig after copying the `.bwextension`.

## User mode (mode 3)

DAW-agnostic **absolute MIDI CC** control. **Mode changes are handled on the firmware** (red/green/blue indicator, user CC grid) — Bitwig does not need to be running. If the LumaFader extension is loaded, it stays in sync via the same mode action CCs and SysEx, but it does **not** map your User-mode fader CCs. Configure **Generic MIDI** (or any host) to receive CCs on channels **1–4**.

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

Hold **button 1** for smaller steps (same as other modes).

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

In Focus mode, navigating **left** from the first device on a track enters **track remote scope**: faders 1–4 (and overlay 5–8) bind to that track’s remote controls page, the arranger focuses the track, and SysEx `remote_scope` reports track (1) for LED semantics.

**The track remotes row in Bitwig’s device panel does not expand automatically.** That is a Bitwig Controller API limitation, not a firmware bug:

- `Device.isRemoteControlsSectionVisible()` can toggle the remote strip for a **device** in the chain; there is no equivalent on `Track`.
- `Track.createCursorRemoteControlsPage()` exposes parameters for mapping/control only, not panel visibility.
- The device cursor stays on the selected plugin when entering track remotes; `selectChannel` / `selectInEditor` focus the track but do not move the cursor to a track-header “device.”
- No hidden Bitwig **Application** actions expose track-remote panel visibility either.

**Accepted behavior for now:** hardware controls track remotes correctly; open the track remotes strip in the device panel manually if you want it visible on screen.

## Possible future features

Not planned for the current release; listed here so ideas stay visible without committing to UI or firmware complexity yet.

### Upside-down / “handedness” mode

A setting to flip the logical control layout (buttons and fader indices mirrored) so the unit could be used with the USB port on the opposite edge — useful if you prefer the faders on the other side.

**Why we’re holding off:** in that orientation the cable exits toward the player and tends to wrap around the body. The physical gap between the button row and the fader caps is already large enough that one-handed use is awkward either way, so the ergonomic win is unclear. If demand shows up, a toggle plus clear LED/fader remapping would be the shape of the work.

### User mode: configurable view → hold mapping

Focus and Four-Track modes let you assign each fader layer to **Default** or a hold button (see web config). User mode still uses a **fixed** map: view 0 = no hold, views 1–3 = hold buttons 4, 3, 2 (`user_mode.py`).

**Why we’re holding off:** remapping only changes which button selects which slot in `USER_CC_GRID`; it doesn’t add capability, and the CC grid UI is already dense. Most “weird” orders (e.g. view 1 on button 2 instead of 4) are edge cases; a simple **hold-order preset** (top-down vs bottom-up) would cover most of them without a full four-way assignment UI. Revisit if users ask for ergonomic or legacy-controller parity reasons.

## Reference (read-only)

- `../Midi-Slider-Cherry` — original firmware
- `../DrivenByMoss` — Bitwig extension patterns

## Acknowledgments

Firmware in this repo derives from [Midi-Slider-Cherry](https://github.com/derrickthomin/Midi-Slider-Cherry) (DJBB LumaFader). The Bitwig extension, protocol changes, web config, and most Bitwig-specific firmware work were developed here with substantial help from **AI-assisted coding in [Cursor](https://cursor.com)** — design, implementation, and documentation were iterated collaboratively (human direction, agent edits). Credit where it is due.
