# boot.py runs once per hard reset, before code.py.
# Pico PCB BOOT + USB = RPI-RP2 UF2 loader only (not handled here).

import json
import storage
import microcontroller
import supervisor
import usb_midi

supervisor.set_usb_identification(manufacturer="DJBB", product="LumaFader")
microcontroller.cpu.frequency = 270_000_000

storage.remount("/", readonly=False)

m = storage.getmount("/")
m.label = "LUMAFADER"

usb_drive_at_boot = False
try:
    with open("/settings.json", "r") as f:
        usb_drive_at_boot = json.load(f).get("USB_DRIVE_AT_BOOT", False)
except Exception:
    pass

if usb_drive_at_boot:
    storage.enable_usb_drive()
else:
    storage.disable_usb_drive()

# After all other USB identity/storage setup — set_usb_identification can reset descriptors.
usb_midi.set_names(
    streaming_interface_name="LumaFader",
    audio_control_interface_name="LumaFader",
    in_jack_name="LumaFader",
    out_jack_name="LumaFader",
)
