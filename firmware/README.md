# LumaFader Bitwig Firmware

CircuitPython firmware for the DJBB LumaFader, designed to pair with the LumaFader Bitwig Studio extension.

## Requirements

- Raspberry Pi Pico (RP2040) on the LumaFader hardware
- [CircuitPython 8.2.6](https://circuitpython.org/board/raspberry_pi_pico/) (see `uf2/` when added, or use the reference repo)
- Bundled libraries on `CIRCUITPY/lib/`:
  - `adafruit_midi` (+ `adafruit_bus_device`)
  - `adafruit_debouncer`
  - `neopixel`

Copy those libraries from a CircuitPython bundle matching 8.2.x if they are not already on the device.

## Flashing (same workflow as stock LumaFader)

1. **Enter boot mode:** Hold **BOOT** on the Pico, plug in USB (or hold BOOT and tap RESET). Release BOOT. Mount **RPI-RP2** appears.

2. **Optional nuke:** Drag `uf2/flash_nuke.uf2` onto **RPI-RP2** (wipes flash). Device reboots as **RPI-RP2** again.

3. **Install CircuitPython:** Drag `uf2/adafruit-circuitpython-raspberry_pi_pico-en_US-8.2.6.uf2` onto **RPI-RP2**. Device reboots as **CIRCUITPY** (or **LUMAFADER**).

4. **Copy firmware:** Copy everything under `firmware/src/` onto the device drive (including `settings.json`). Overwrite when prompted.

5. **Done.** Unplug and reconnect. Normal boot hides the USB drive; hold all four buttons while plugging in to expose **LUMAFADER** for drag-and-drop or Web Serial config.

## Developer deploy script

From the repo root:

```bash
python3 firmware/scripts/initializer.py
```

Edit paths at the top of `initializer.py` for your machine. The script can flash UF2 and copy `firmware/src/` to `/Volumes/CIRCUITPY`, `/Volumes/LUMAFADER`, or `/Volumes/LUMA`.

## Configuration

- **settings.json** on the device: MIDI channel, gesture bindings, fine-control scale, etc.
- **Web Serial:** Same `CMD:` / `RSP:` protocol as stock firmware (`serial_config.py`). Use the Web Config utility from the reference project until a Bitwig-specific config UI exists.

Restart the device after saving settings from the web utility.

## Protocol summary

| Direction | Transport | Purpose |
|-----------|-----------|---------|
| Controller → Bitwig | Relative CC, notes, gesture CCs | Fader deltas, buttons, navigation gestures |
| Bitwig → Controller | SysEx (`0x7D` dev ID) | LED layout, workspace/overlay/scope, nav-reject flashes |

See `lumafader_bitwig_v1_spec.txt` at the repo root and `src/sysex_protocol.py` for message layouts.
