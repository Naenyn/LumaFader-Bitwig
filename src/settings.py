import json

import constants as cfg

# Every binding name in BINDINGS should have a matching ACTION_CC entry.
ACTION_CC_KEYS = (
    "workspace_focus",
    "workspace_four_track",
    "workspace_user",
    "scope_toggle",
    "nav_next_track",
    "nav_prev_track",
    "nav_next_device",
    "nav_prev_device",
    "nav_next_track_page",
    "nav_prev_track_page",
    "nav_next_send",
    "nav_prev_send",
    "overlay_1",
    "overlay_2",
    "overlay_3",
    "fine_modifier",
)

# Keep clear of FADER_CC_SENDS (28–31) and FADER_CC_UTILITY (32–35).
DEFAULT_ACTION_CC = {
    "workspace_focus": 60,
    "workspace_four_track": 61,
    "workspace_user": 62,
    "scope_toggle": 63,
    "nav_next_track": 40,
    "nav_prev_track": 41,
    "nav_next_device": 42,
    "nav_prev_device": 43,
    "nav_next_track_page": 44,
    "nav_prev_track_page": 45,
    "nav_next_send": 46,
    "nav_prev_send": 47,
    "overlay_1": 50,
    "overlay_2": 51,
    "overlay_3": 52,
    "fine_modifier": 53,
}


class Settings:
    DEFAULTS = {
        "PROTOCOL_VERSION": 1,
        "USB_DRIVE_AT_BOOT": False,
        "MIDI_CHANNEL": 1,
        "FADER_CC": [20, 21, 22, 23],
        "FADER_CC_OVERLAY": [24, 25, 26, 27],
        "FADER_CC_SENDS": [28, 29, 30, 31],
        "FADER_CC_UTILITY": [32, 33, 34, 35],
        "FINE_MODIFIER_BUTTON": cfg.BUTTON_1,
        "FINE_SCALE": 0.25,
        "DELTA_SCALE": 1.0,
        "ACTION_CC": dict(DEFAULT_ACTION_CC),
        "BINDINGS": {
            "workspace_focus": {"type": "double_tap", "button": cfg.BUTTON_4},
            "workspace_four_track": {"type": "double_tap", "button": cfg.BUTTON_3},
            "workspace_user": {"type": "double_tap", "button": cfg.BUTTON_2},
            "scope_toggle": {"type": "double_tap", "button": cfg.BUTTON_4},
            "nav_next_track": {"type": "chord", "hold": cfg.BUTTON_4, "tap": cfg.BUTTON_1},
            "nav_prev_track": {"type": "chord", "hold": cfg.BUTTON_1, "tap": cfg.BUTTON_4},
            "nav_next_device": {"type": "chord", "hold": cfg.BUTTON_3, "tap": cfg.BUTTON_2},
            "nav_prev_device": {"type": "chord", "hold": cfg.BUTTON_2, "tap": cfg.BUTTON_3},
            "nav_next_track_page": {"type": "chord", "hold": cfg.BUTTON_4, "tap": cfg.BUTTON_1},
            "nav_prev_track_page": {"type": "chord", "hold": cfg.BUTTON_1, "tap": cfg.BUTTON_4},
            "nav_next_send": {"type": "chord", "hold": cfg.BUTTON_3, "tap": cfg.BUTTON_2},
            "nav_prev_send": {"type": "chord", "hold": cfg.BUTTON_2, "tap": cfg.BUTTON_3},
            "overlay_1": {"type": "hold", "button": cfg.BUTTON_4},
            "overlay_2": {"type": "hold", "button": cfg.BUTTON_3},
            "overlay_3": {"type": "hold", "button": cfg.BUTTON_2},
        },
    }

    OVERLAY_ACTIONS = ("overlay_1", "overlay_2", "overlay_3")

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
        for key in ("MIDI_CHANNEL", "FADER_CC", "ACTION_CC", "BINDINGS"):
            if key not in self.settings:
                return False
        if len(self.settings["FADER_CC"]) != 4:
            return False
        if len(self.settings.get("FADER_CC_SENDS", self.DEFAULTS["FADER_CC_SENDS"])) != 4:
            return False
        if len(self.settings.get("FADER_CC_UTILITY", self.DEFAULTS["FADER_CC_UTILITY"])) != 4:
            return False
        action_cc = self.settings["ACTION_CC"]
        for name in ACTION_CC_KEYS:
            if name not in action_cc:
                return False
            cc = action_cc[name]
            if not isinstance(cc, int) or cc < 0 or cc > 127:
                return False
        return True

    def _use_defaults(self):
        self.settings = dict(self.DEFAULTS)
        self.settings["ACTION_CC"] = dict(DEFAULT_ACTION_CC)

    def get_midi_channel(self):
        ch = int(self.settings.get("MIDI_CHANNEL", 1))
        return max(0, min(15, ch - 1))

    def get_fader_cc(self, index, overlay_bank="default"):
        """overlay_bank: 'default' | 'remotes_5_8' | 'sends' | 'utility'."""
        if overlay_bank == "remotes_5_8":
            key = "FADER_CC_OVERLAY"
        elif overlay_bank == "sends":
            key = "FADER_CC_SENDS"
        elif overlay_bank == "utility":
            key = "FADER_CC_UTILITY"
        else:
            key = "FADER_CC"
        bank = self.settings.get(key, self.DEFAULTS[key])
        return int(bank[index])

    def get_action_cc(self, action_name):
        return int(self.settings["ACTION_CC"][action_name])

    def get_fine_modifier_button(self):
        return int(self.settings.get("FINE_MODIFIER_BUTTON", cfg.BUTTON_1))

    def get_fine_scale(self):
        return float(self.settings.get("FINE_SCALE", 0.25))

    def get_delta_scale(self):
        return float(self.settings.get("DELTA_SCALE", 1.0))

    def get_binding(self, name):
        return self.settings.get("BINDINGS", {}).get(name)

    def is_overlay_action(self, action_name):
        return action_name in self.OVERLAY_ACTIONS


settings = Settings()
