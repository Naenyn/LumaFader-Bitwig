import constants as cfg
from settings import settings


class GestureEngine:
    """Detects double-tap mode switches, overlay holds, and nav chords."""

    GESTURE_MAP = {
        "mode_focus": (cfg.GESTURE_MODE, cfg.MODE_FOCUS),
        "mode_four_track": (cfg.GESTURE_MODE, cfg.MODE_FOUR_TRACK),
        "mode_user": (cfg.GESTURE_MODE, cfg.MODE_USER),
        "nav_next_track": (cfg.GESTURE_NAV_NEXT_TRACK, 0),
        "nav_prev_track": (cfg.GESTURE_NAV_PREV_TRACK, 0),
        "nav_next_device": (cfg.GESTURE_NAV_NEXT_DEVICE, 0),
        "nav_prev_device": (cfg.GESTURE_NAV_PREV_DEVICE, 0),
        "nav_next_track_page": (cfg.GESTURE_NAV_NEXT_TRACK_PAGE, 0),
        "nav_prev_track_page": (cfg.GESTURE_NAV_PREV_TRACK_PAGE, 0),
        "nav_next_send": (cfg.GESTURE_NAV_NEXT_SEND, 0),
        "nav_prev_send": (cfg.GESTURE_NAV_PREV_SEND, 0),
    }

    OVERLAY_MAP = {
        "overlay_1": 1,
        "overlay_2": 2,
        "overlay_3": 3,
    }

    def __init__(self, buttons):
        self.buttons = buttons
        self.active_overlay = 0
        self._pending_gestures = []

    def update(self):
        self._pending_gestures = []
        self._detect_double_taps()
        self._detect_chords()
        self.active_overlay = self._detect_overlay_hold()
        return list(self._pending_gestures)

    def _detect_double_taps(self):
        for name, binding in settings.settings.get("BINDINGS", {}).items():
            if not binding or binding.get("type") != "double_tap":
                continue
            btn_idx = binding.get("button", -1)
            if btn_idx < 0 or btn_idx >= len(self.buttons):
                continue
            if self.buttons[btn_idx].was_double_pressed:
                gesture = self.GESTURE_MAP.get(name)
                if gesture:
                    self._pending_gestures.append(gesture)

    def _detect_chords(self):
        for name, binding in settings.settings.get("BINDINGS", {}).items():
            if not binding or binding.get("type") != "chord":
                continue
            hold_idx = binding.get("hold", -1)
            tap_idx = binding.get("tap", -1)
            if hold_idx < 0 or tap_idx < 0:
                continue
            hold_btn = self.buttons[hold_idx]
            tap_btn = self.buttons[tap_idx]
            if (
                hold_btn.pressed
                and tap_btn.detected_new_release
                and not tap_btn.was_long_held
                and tap_btn.last_press_duration <= cfg.CHORD_TAP_MAX_S
            ):
                gesture = self.GESTURE_MAP.get(name)
                if gesture:
                    self._pending_gestures.append(gesture)

    def _detect_overlay_hold(self):
        held = []
        for name, overlay_id in self.OVERLAY_MAP.items():
            binding = settings.get_binding(name)
            if not binding or binding.get("type") != "hold":
                continue
            btn_idx = binding.get("button", -1)
            if 0 <= btn_idx < len(self.buttons) and self.buttons[btn_idx].pressed:
                held.append(overlay_id)

        if not held:
            return 0
        return held[-1]

    def overlay_hold_gesture(self):
        if self.active_overlay == 0:
            return None
        return (cfg.GESTURE_OVERLAY_HOLD, self.active_overlay)
