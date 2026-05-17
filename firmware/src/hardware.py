import board
import neopixel
import storage
import time

import constants as cfg


class PixelHardware:
    """NeoPixel layout for the LumaFader (69 pixels)."""

    NUM_PIXELS = 69
    INDICATOR_INDEX = 68

    def __init__(self, brightness=0.2):
        self.pixels = neopixel.NeoPixel(
            board.GP15,
            self.NUM_PIXELS,
            brightness=brightness,
            auto_write=False,
        )
        self.button_indices = {0: 0, 1: 1, 2: 2, 3: 3}
        self.slider_indices = {
            0: list(range(4, 20)),
            1: list(range(20, 36)),
            2: list(range(36, 52)),
            3: list(range(52, 68)),
        }
        self.clear()

    def clear(self):
        self.pixels.fill(cfg.COLOR_OFF)

    def show(self):
        self.pixels.show()

    def startup_animation(self):
        readonly = storage.getmount("/").readonly
        if readonly:
            for _ in range(3):
                self.pixels.fill(cfg.COLOR_REJECT)
                self.show()
                time.sleep(0.2)
                self.clear()
                self.show()
                time.sleep(0.2)
        self._rainbow(duration=2.0)
        self.clear()
        self.show()

    def _rainbow(self, duration=2.0):
        start = time.monotonic()

        def wheel(pos):
            if pos < 85:
                return (255 - pos * 3, pos * 3, 0)
            if pos < 170:
                pos -= 85
                return (0, 255 - pos * 3, pos * 3)
            pos -= 170
            return (pos * 3, 0, 255 - pos * 3)

        j = 0
        while time.monotonic() - start < duration:
            for i in range(self.NUM_PIXELS):
                pos = (i * 256 * 2 // self.NUM_PIXELS + j) % 256
                self.pixels[i] = wheel(pos)
            self.show()
            time.sleep(0.002)
            j = (j + 1) % 256
