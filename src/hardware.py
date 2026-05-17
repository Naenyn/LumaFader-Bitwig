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
        """Stock LumaFader rainbow (~2s), with red blink if USB is read-only."""
        readonly = storage.getmount("/").readonly
        if readonly:
            for _ in range(3):
                self.pixels.fill((255, 0, 0))
                self.show()
                time.sleep(0.2)
                self.pixels.fill(cfg.COLOR_OFF)
                self.show()
                time.sleep(0.2)

        self.rainbow_animation(speed=0.002, cycles=2, duration=2.0)
        self.clear()
        self.show()

    def rainbow_animation(self, speed=0.01, cycles=3, duration=None):
        def wheel(pos):
            if pos < 85:
                return (255 - pos * 3, pos * 3, 0)
            if pos < 170:
                pos -= 85
                return (0, 255 - pos * 3, pos * 3)
            pos -= 170
            return (pos * 3, 0, 255 - pos * 3)

        start_time = time.monotonic()
        try:
            while True:
                if duration is not None and (time.monotonic() - start_time) > duration:
                    break
                for j in range(256):
                    if duration is not None and (time.monotonic() - start_time) > duration:
                        break
                    for i in range(self.NUM_PIXELS):
                        position = (i * 256 * cycles // self.NUM_PIXELS + j) % 256
                        self.pixels[i] = wheel(position)
                    self.show()
                    time.sleep(speed)
        except KeyboardInterrupt:
            pass
