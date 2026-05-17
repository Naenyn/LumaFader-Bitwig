import time

import analogio
import digitalio
from adafruit_debouncer import Debouncer

import constants as cfg


class MidiSlider:
    """Reads a fader ADC channel and tracks smoothed position + delta."""

    def __init__(self, analog_pin, slider_index):
        self.analog_pin = analog_pin
        self.index = slider_index
        self.last_value = 0
        self.current_value = 0
        self.smoothed_value = 0
        self.position = 0
        self.physical_position = 0
        self.last_position = 0
        self.delta = 0
        self.changed = False

        self.adaptive_buffer = []
        self.adaptive_state = "STABLE"
        self.adaptive_last_position = 0
        self.adaptive_last_send_time = time.monotonic()
        self._primed = False

    def update(self):
        self.current_value = 65536 - self.analog_pin.value
        now = time.monotonic()

        if not self._primed:
            self.smoothed_value = float(self.current_value)
            self.position = self._raw_to_position(self.smoothed_value)
            self.physical_position = self.position
            self.last_position = self.position
            self.adaptive_last_position = self.position
            self.adaptive_last_send_time = now
            self.adaptive_buffer = [self.position]
            self._primed = True
            self.delta = 0
            self.changed = False
            self.last_value = self.current_value
            return False

        smoothed_raw = (
            cfg.ADAPTIVE_SMOOTHING_FACTOR * self.current_value
            + (1 - cfg.ADAPTIVE_SMOOTHING_FACTOR) * self.smoothed_value
        )
        self.smoothed_value = smoothed_raw
        position = self._raw_to_position(smoothed_raw)
        self.physical_position = position

        self.adaptive_buffer.append(position)
        if len(self.adaptive_buffer) > cfg.ADAPTIVE_BUFFER_SIZE:
            self.adaptive_buffer.pop(0)

        threshold = (
            cfg.ADAPTIVE_STABLE_THRESHOLD_CC
            if self.adaptive_state == "STABLE"
            else cfg.ADAPTIVE_MOVING_THRESHOLD_CC
        )

        should_update = abs(position - self.adaptive_last_position) >= threshold

        if self.adaptive_state == "CHANGING":
            if now - self.adaptive_last_send_time >= cfg.ADAPTIVE_HOLD_DURATION:
                self.adaptive_state = "STABLE"
        elif should_update:
            self.adaptive_state = "CHANGING"

        if should_update:
            self.last_position = self.position
            self.position = position
            self.delta = self.position - self.last_position
            self.adaptive_last_position = position
            self.adaptive_last_send_time = now
            self.changed = True
        else:
            self.delta = 0
            self.changed = False

        self.last_value = self.current_value
        return self.changed

    def _raw_to_position(self, raw):
        return max(0, min(127, int(raw / cfg.ADAPTIVE_RAW_TO_CC_DIVISOR)))

    def consume_delta(self):
        d = self.delta
        self.delta = 0
        self.changed = False
        return d

class BankButton:
    """Debounced button with hold and double-press detection."""

    def __init__(self, digital_pin):
        self.digital_pin = digital_pin
        self.digital_pin.direction = digitalio.Direction.INPUT
        self.digital_pin.pull = digitalio.Pull.UP
        self.button = Debouncer(self.digital_pin)
        self._last_press_time = 0
        self._hold_time = 0
        self._is_long_held = False
        self._was_long_held = False
        self._double_press_detected = False
        self.detected_new_release = False
        self.detected_new_press = False

    def update(self):
        self.button.update()
        state_changed = False
        self.detected_new_release = False
        self.detected_new_press = False
        self._double_press_detected = False
        self._was_long_held = False
        current_time = time.monotonic()

        if self.button.fell:
            if (current_time - self._last_press_time) <= cfg.DOUBLE_PRESS_TIME:
                self._double_press_detected = True
            self._last_press_time = current_time
            self._hold_time = 0.01
            state_changed = True
            self.detected_new_press = True
        elif self.button.rose:
            self._hold_time = 0
            state_changed = True
            self.detected_new_release = True
            self._was_long_held = self._is_long_held
            self._is_long_held = False
        elif not self.button.value:
            self._hold_time = current_time - self._last_press_time
            if self._hold_time >= cfg.LONG_HOLD_THRESH_S and not self._is_long_held:
                self._is_long_held = True
        else:
            self._hold_time = 0
            self._is_long_held = False

        return state_changed

    @property
    def pressed(self):
        return not self.button.value

    @property
    def hold_time(self):
        return self._hold_time

    @property
    def was_long_held(self):
        return self._was_long_held

    @property
    def was_double_pressed(self):
        return self._double_press_detected
