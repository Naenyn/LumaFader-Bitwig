# Deploying firmware

See [Development requirements](../README.md#development-requirements) in the README for toolchains (Python, mpremote, Bitwig, JDK, etc.).

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
| **Web Serial** | Open `docs/web_config.html` in Chrome/Edge → Connect → edit → Save. Same `CMD:` / `RSP:` protocol as stock LumaFader (`serial_config.py`). Hard-reset after save. |
| **Deploy script** | `python3 scripts/deploy.py` — copies `firmware/src/` including `settings.json` over mpremote. |

**Serial port (one client at a time):** macOS and most hosts allow only one program to open the device's USB serial port. If **web_config.html** is connected (Web Serial), disconnect there (or close the tab) before running `python3 scripts/deploy.py`. The same applies in reverse — finish or abort deploy before connecting in the browser. If deploy still fails with “port in use”, quit **Bitwig** (the extension may hold the port).

**Config boot notes:** Enabling the USB drive in `boot.py` may **re-enumerate USB** once (you might see startup twice — keep all four buttons held through plug-in). **After eject**, macOS often triggers a **soft reload** (`code.py` only; `boot.py` does not run). The drive will **not** remount until the next four-button hard reset; MIDI should still work. Eject should **not** repeat the red config blink (firmware clears a one-shot NVM flag after the first startup animation).

Normal boot hides the USB drive and keeps the short MIDI port name **LumaFader**. The longer CircuitPython MIDI name on macOS is expected while the drive is exposed.

## One-time / upgrade CircuitPython

Download UF2 files into `firmware/uf2/` first (not in git) — see [README](../README.md#circuitpython-uf2-files-firmwareuf2).

1. Hold **Pico BOOT**, plug USB → `RPI-RP2` appears.
2. Drag `firmware/uf2/adafruit-circuitpython-raspberry_pi_pico-en_US-9.2.8.uf2` onto `RPI-RP2`.
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
- Default: drive hidden at boot → short name **LumaFader** MIDI app sources
- While **LUMAFADER** is mounted (four-button plug-in), macOS may show the longer name (`LumaFader CircuitPython usb_m…`); that is fine for config sessions. Hard-reset without the drive for the short name again.
- If the name sticks after config on macSO, disconnect the LumaFader and remove the LumaFader from Audio MIDI Setup.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Controls dead, no MIDI | Incompatible `lib/` — run `./scripts/install_libs.sh` after CP upgrade |
| USB drive on every boot | Check `boot_out.txt` on the device — a `boot.py` crash skips `storage.disable_usb_drive()`. Redeploy `boot.py`, hard reset. |
| Red blink on every boot (no buttons) | Same as above — `boot.py` must finish; early crash on read-only FS was a common cause (fixed: remount writable before setting volume label). |
| Red blink again after eject | Stale config flag on old firmware; deploy latest `code.py` (clears NVM after startup). |
| `mpremote`: port in use / failed to access | Close **web_config.html** or click **Disconnect**; quit Bitwig if needed. Only one app can use USB serial at a time. |
| `mpremote` deploy: read-only FS | After config mode, run `mpremote connect auto exec "import storage; storage.remount('/', readonly=False)"` then deploy again, or copy via **LUMAFADER** while mounted. |
| Long MIDI name in MIDI app | Normal boot without USB drive; edit settings as desired; disconnect LumeFader; if macOS, remove LumeFader from Audio MIDI Setup; reconnect LumeFader; reopen MIDI app |
| `incompatible .mpy file` | Same as above — CP 9 libs required |
