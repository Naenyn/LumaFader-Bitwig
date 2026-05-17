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
| **Four buttons** | Hold **all four** front buttons, plug USB, keep holding until **LUMAFADER** mounts and the rainbow startup finishes (often ~5–8 s). **Red blink ×3** then rainbow = config mode. Edit `settings.json`, eject from the host, then **hard reset** (unplug/replug, no buttons) for normal MIDI-only mode. |
| **Web Serial** | Browser config UI over USB serial (`CMD:` / `RSP:` in `serial_config.py`). No drive mount; restart after save. |
| **Deploy script** | `python3 scripts/deploy.py` — copies `src/` including `settings.json` over mpremote. |

**Config boot notes:** Enabling the USB drive in `boot.py` may **re-enumerate USB** once (you might see startup twice — keep all four buttons held through plug-in). **After eject**, macOS often triggers a **soft reload** (`code.py` only; `boot.py` does not run). The drive will **not** remount until the next four-button hard reset; MIDI should still work. Eject should **not** repeat the red config blink (firmware clears a one-shot NVM flag after the first startup animation).

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
- While **LUMAFADER** is mounted (four-button plug-in), macOS may show the longer name (`LumaFader CircuitPython usb_m…`); that is fine for config sessions. Hard-reset without the drive for the short name again.
- If the name sticks after config, quit ShowMIDI and replug; macOS caches MIDI device names.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Controls dead, no MIDI | Incompatible `lib/` — run `./scripts/install_libs.sh` after CP upgrade |
| USB drive on every boot | Check `boot_out.txt` on the device — a `boot.py` crash skips `storage.disable_usb_drive()`. Redeploy `boot.py`, hard reset. |
| Red blink on every boot (no buttons) | Same as above — `boot.py` must finish; early crash on read-only FS was a common cause (fixed: remount writable before setting volume label). |
| Red blink again after eject | Stale config flag on old firmware; deploy latest `code.py` (clears NVM after startup). |
| `mpremote` deploy: read-only FS | After config mode, run `mpremote connect auto exec "import storage; storage.remount('/', readonly=False)"` then deploy again, or copy via **LUMAFADER** while mounted. |
| Long MIDI name in ShowMIDI | Normal boot without USB drive; hard reset after editing settings; reopen ShowMIDI |
| `incompatible .mpy file` | Same as above — CP 9 libs required |
