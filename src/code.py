import time

import board
import digitalio
import analogio

from controller import LumaFaderController
from hardware import PixelHardware
from led_renderer import LedRenderer
from midi import create_midi_manager
from serial_config import serial_config
from visible_state import VisibleState

slider_pins = [
    analogio.AnalogIn(board.A0),
    analogio.AnalogIn(board.A1),
    analogio.AnalogIn(board.A2),
    analogio.AnalogIn(board.A3),
]

button_pins = [
    digitalio.DigitalInOut(board.GP0),
    digitalio.DigitalInOut(board.GP1),
    digitalio.DigitalInOut(board.GP2),
    digitalio.DigitalInOut(board.GP3),
]

for pin in button_pins:
    pin.direction = digitalio.Direction.INPUT
    pin.pull = digitalio.Pull.UP

visible_state = VisibleState()
hardware = PixelHardware()
hardware.startup_animation()

midi_manager = create_midi_manager(visible_state)
led_renderer = LedRenderer(hardware, visible_state)
controller = LumaFaderController(slider_pins, button_pins, midi_manager, visible_state)

serial_config.set_controller(controller)
serial_config.set_midi(midi_manager)

while True:
    now = time.monotonic()
    midi_manager.poll_incoming()
    controller.update_inputs()
    controller.process()
    serial_config.update()
    led_renderer.render(now, controller)
    time.sleep(0.0001)
