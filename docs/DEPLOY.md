# Deploying firmware

## What “Pico BOOT” means

| Plug-in | Volume / port | Purpose |
|---------|----------------|---------|
| **Pico BOOT** held | `RPI-RP2` | Flash `.uf2` only (CircuitPython install/upgrade) |
| **Normal** plug-in | USB MIDI + serial | Run firmware; update `.py` with **mpremote** |

The four front-panel buttons are not used for Pico BOOT, but **are** used to expose the settings drive (see below).

## Editing settings on the device

| Method | How |
|--------|-----|
| **Four buttons** (default) | Hold **all four** front buttons, plug USB, release when **LUMAFADER** mounts. Edit `settings.json`, eject, **hard reset**. |
| **Always-on drive** | `USB_DRIVE_AT_BOOT: true` in `settings.json` — **LUMAFADER** on every normal plug-in. |
| **Web Serial** | Browser config UI over USB serial (`CMD:` / `RSP:` in `serial_config.py`). No drive mount; restart after save. |
| **Deploy script** | `python3 scripts/deploy.py` — copies `src/` including `settings.json` over mpremote. |

Normal boot hides the USB drive and keeps the short MIDI port name **LumaFader**. The longer CircuitPython MIDI name on macOS is expected while the drive is exposed.

## One-time / upgrade CircuitPython

1. Hold **Pico BOOT**, plug USB → `RPI-RP2` appears.
2. Drag `uf2/adafruit-circuitpython-raspberry_pi_pico-en_US-9.2.8.uf2` onto `RPI-RP2`.
3. Release BOOT, replug normally.
4. Install libraries (required after any CP major upgrade):

   ```bash
   ./scripts/install_libs.sh
   ```

5. Deploy firmware:

   ```bash
   pipx install mpremote   # once
   python3 scripts/deploy.py
   ```

6. **Hard reset** (unplug/replug). `boot.py` only runs after a power-cycle, not soft reload.

## Routine firmware edits

```bash
python3 scripts/deploy.py
```

Then **unplug and replug** (hard reset) so `boot.py` changes apply.

## MIDI port name “LumaFader”

- Set in `boot.py` via `usb_midi.set_names()` (needs CircuitPython **9+**).
- Default: drive hidden at boot → short name **LumaFader** in ShowMIDI etc.
- While **LUMAFADER** is mounted (four-button plug-in or `USB_DRIVE_AT_BOOT`), macOS may show the longer name (`LumaFader CircuitPython usb_m…`); that is fine for config sessions. Hard-reset without the drive for the short name again.
- If the name sticks after config, quit ShowMIDI and replug; macOS caches MIDI device names.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Controls dead, no MIDI | Incompatible `lib/` — run `./scripts/install_libs.sh` after CP upgrade |
| Long MIDI name in ShowMIDI | Normal boot without USB drive; hard reset after editing settings; reopen ShowMIDI |
| `incompatible .mpy file` | Same as above — CP 9 libs required |
