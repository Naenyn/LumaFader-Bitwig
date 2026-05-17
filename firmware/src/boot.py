import storage
import board
import digitalio
import microcontroller
import supervisor

supervisor.set_usb_identification(manufacturer="DJBB", product="LumaFader-Bitwig")
microcontroller.cpu.frequency = 270_000_000

storage.remount("/", readonly=False)

m = storage.getmount("/")
m.label = "LUMAFADER"

btn_pins = [board.GP0, board.GP1, board.GP2, board.GP3]
buttons = []
for pin in btn_pins:
    btn = digitalio.DigitalInOut(pin)
    btn.direction = digitalio.Direction.INPUT
    btn.pull = digitalio.Pull.UP
    buttons.append(btn)

all_pressed = all(not b.value for b in buttons)

storage.remount("/", readonly=all_pressed)

if all_pressed:
    storage.enable_usb_drive()
else:
    storage.disable_usb_drive()
