import time

import constants as cfg
from gestures import GestureEngine
from inputs import BankButton, MidiSlider
from settings import settings


class LumaFaderController:
    """Hardware input scanning, gesture detection, and MIDI output."""

    def __init__(self, slider_pins, button_pins, midi, visible_state):
        self.midi = midi
        self.visible_state = visible_state
        self.sliders = [MidiSlider(p, i) for i, p in enumerate(slider_pins)]
        self.buttons = [BankButton(p) for p in button_pins]
        self.gestures = GestureEngine(self.buttons)
        self.channel = settings.get_midi_channel()
        self.config_mode = False
        self._button_was_pressed = [False] * 4

    def update_inputs(self):
        changed = False
        for slider in self.sliders:
            if slider.update():
                changed = True
        for button in self.buttons:
            if button.update():
                changed = True
        return changed

    def process(self):
        self._send_button_notes()
        self._send_fader_deltas()
        self._send_gestures()

    def _fine_active(self):
        fine_btn = settings.get_fine_modifier_button()
        if 0 <= fine_btn < len(self.buttons):
            return self.buttons[fine_btn].pressed
        return False

    def _send_fader_deltas(self):
        scale = settings.get_delta_scale()
        if self._fine_active():
            scale *= settings.get_fine_scale()

        fine_cc = settings.get_fine_modifier_cc()
        self.midi.send_fine_modifier(self._fine_active(), fine_cc, self.channel)

        for i, slider in enumerate(self.sliders):
            if self.visible_state.fader_mode[i] == cfg.FADER_MODE_DIM:
                slider.consume_delta()
                continue

            delta = slider.consume_delta()
            if delta == 0:
                continue

            cc = settings.get_fader_cc(i)
            self.midi.send_scaled_relative_cc(cc, delta, self.channel, scale)

    def _send_button_notes(self):
        for i, button in enumerate(self.buttons):
            pressed = button.pressed
            if pressed == self._button_was_pressed[i]:
                continue
            self._button_was_pressed[i] = pressed
            if button.detected_new_press or button.detected_new_release:
                note = settings.get_button_note(i)
                self.midi.send_button_note(note, pressed, self.channel)

    def _send_gestures(self):
        gesture_cc = settings.get_gesture_cc()
        for gesture_id, param in self.gestures.update():
            self.midi.send_gesture(gesture_id, param, gesture_cc, self.channel)

        overlay = self.gestures.overlay_hold_gesture()
        if overlay:
            self.midi.send_gesture(overlay[0], overlay[1], gesture_cc, self.channel)
