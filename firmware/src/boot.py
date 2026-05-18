# boot.py runs once per hard reset, before code.py.
# Pico PCB BOOT + USB = RPI-RP2 UF2 loader only (not handled here).

import time

import board
import digitalio
import storage
import microcontroller
import supervisor
import usb_midi

btn_pins = [board.GP0, board.GP1, board.GP2, board.GP3]
buttons = []
for pin in btn_pins:
    btn = digitalio.DigitalInOut(pin)
    btn.direction = digitalio.Direction.INPUT
    btn.pull = digitalio.Pull.UP
    buttons.append(btn)


def _all_buttons_held(samples=12, interval_s=0.015):
    """Active-low; require held across samples after power-on settle."""
    time.sleep(0.1)
    for _ in range(samples):
        if not all(not b.value for b in buttons):
            return False
        time.sleep(interval_s)
    return True


# Decide config mode before USB/storage changes (avoids a false "normal" boot pass).
expose_usb_drive = _all_buttons_held()
microcontroller.nvm[0:1] = bytes([1 if expose_usb_drive else 0])

supervisor.set_usb_identification(manufacturer="DJBB", product="LumaFader")
microcontroller.cpu.frequency = 270_000_000

usb_midi.set_names(
    streaming_interface_name="LumaFader",
    audio_control_interface_name="LumaFader",
    in_jack_name="LumaFader",
    out_jack_name="LumaFader",
)

storage.remount("/", readonly=False)

m = storage.getmount("/")
m.label = "LUMAFADER"

if expose_usb_drive:
    storage.enable_usb_drive()
else:
    storage.disable_usb_drive()
