# Deploying firmware

## What “Pico BOOT” means

| Plug-in | Volume / port | Purpose |
|---------|----------------|---------|
| **Pico BOOT** held | `RPI-RP2` | Flash `.uf2` only (CircuitPython install/upgrade) |
| **Normal** plug-in | USB MIDI + serial | Run firmware; update `.py` with **mpremote** |

The four front-panel faders/buttons are not involved in bootloader or deploy.

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
- Keep `USB_DRIVE_AT_BOOT` **false** in `settings.json` — enabling the USB drive on Mac can re-enumerate USB and restore the long default name (`LumaFader CircuitPython usb_m…`).
- After a name fix, quit ShowMIDI and replug the device; macOS caches MIDI device names.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Controls dead, no MIDI | Incompatible `lib/` — run `./scripts/install_libs.sh` after CP upgrade |
| Long MIDI name in ShowMIDI | `USB_DRIVE_AT_BOOT: false`, redeploy `boot.py`, hard reset, reopen ShowMIDI |
| `incompatible .mpy file` | Same as above — CP 9 libs required |
