import json

import constants as cfg


class Settings:
    DEFAULTS = {
        "PROTOCOL_VERSION": 1,
        "MIDI_CHANNEL": 1,
        "FADER_CC": [20, 21, 22, 23],
        "BUTTON_NOTES": [60, 61, 62, 63],
        "GESTURE_CC": 110,
        "FINE_MODIFIER_CC": 100,
        "FINE_MODIFIER_BUTTON": cfg.BUTTON_4,
        "FINE_SCALE": 0.25,
        "DELTA_SCALE": 1.0,
        "BINDINGS": {
            "workspace_focus": {"type": "double_tap", "button": cfg.BUTTON_1},
            "workspace_four_track": {"type": "double_tap", "button": cfg.BUTTON_2},
            "workspace_user": {"type": "double_tap", "button": cfg.BUTTON_3},
            "scope_toggle": {"type": "double_tap", "button": cfg.BUTTON_4},
            "nav_next_track": {"type": "chord", "hold": cfg.BUTTON_1, "tap": cfg.BUTTON_4},
            "nav_prev_track": {"type": "chord", "hold": cfg.BUTTON_4, "tap": cfg.BUTTON_1},
            "nav_next_device": {"type": "chord", "hold": cfg.BUTTON_2, "tap": cfg.BUTTON_3},
            "nav_prev_device": {"type": "chord", "hold": cfg.BUTTON_3, "tap": cfg.BUTTON_2},
            "nav_next_track_page": {"type": "chord", "hold": cfg.BUTTON_1, "tap": cfg.BUTTON_4},
            "nav_prev_track_page": {"type": "chord", "hold": cfg.BUTTON_4, "tap": cfg.BUTTON_1},
            "nav_next_send": {"type": "chord", "hold": cfg.BUTTON_2, "tap": cfg.BUTTON_3},
            "nav_prev_send": {"type": "chord", "hold": cfg.BUTTON_3, "tap": cfg.BUTTON_2},
            "overlay_1": {"type": "hold", "button": cfg.BUTTON_1},
            "overlay_2": {"type": "hold", "button": cfg.BUTTON_2},
            "overlay_3": {"type": "hold", "button": cfg.BUTTON_3},
        },
    }

    def __init__(self, settings_path="settings.json"):
        self.settings_path = settings_path
        self.settings = {}
        self.load_settings()

    def load_settings(self):
        try:
            with open(self.settings_path, "r") as f:
                self.settings = json.load(f)
            if not self._validate():
                print("Invalid settings; using defaults.")
                self._use_defaults()
        except Exception as e:
            print(f"Settings load error: {e}. Using defaults.")
            self._use_defaults()

    def _validate(self):
        for key in ("MIDI_CHANNEL", "FADER_CC", "BUTTON_NOTES", "GESTURE_CC", "BINDINGS"):
            if key not in self.settings:
                return False
        if not isinstance(self.settings["FADER_CC"], list) or len(self.settings["FADER_CC"]) != 4:
            return False
        if not isinstance(self.settings["BUTTON_NOTES"], list) or len(self.settings["BUTTON_NOTES"]) != 4:
            return False
        return True

    def _use_defaults(self):
        self.settings = dict(self.DEFAULTS)

    def get_midi_channel(self):
        ch = int(self.settings.get("MIDI_CHANNEL", 1))
        return max(0, min(15, ch - 1))

    def get_fader_cc(self, index):
        return int(self.settings["FADER_CC"][index])

    def get_button_note(self, index):
        return int(self.settings["BUTTON_NOTES"][index])

    def get_gesture_cc(self):
        return int(self.settings.get("GESTURE_CC", 110))

    def get_fine_modifier_cc(self):
        return int(self.settings.get("FINE_MODIFIER_CC", 100))

    def get_fine_modifier_button(self):
        return int(self.settings.get("FINE_MODIFIER_BUTTON", cfg.BUTTON_4))

    def get_fine_scale(self):
        return float(self.settings.get("FINE_SCALE", 0.25))

    def get_delta_scale(self):
        return float(self.settings.get("DELTA_SCALE", 1.0))

    def get_binding(self, name):
        return self.settings.get("BINDINGS", {}).get(name)


settings = Settings()
