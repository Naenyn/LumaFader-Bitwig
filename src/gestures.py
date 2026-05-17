from settings import settings


class GestureEngine:
    """Detects gestures; returns binding names for the controller to map to ACTION_CC."""

    def __init__(self, buttons):
        self.buttons = buttons
        self.active_overlays = set()
        self._pending_actions = []

    def update(self):
        self._pending_actions = []
        self._detect_double_taps()
        self._detect_chords()
        self.active_overlays = self._detect_overlay_holds()
        return list(self._pending_actions)

    def _detect_double_taps(self):
        for name, binding in settings.settings.get("BINDINGS", {}).items():
            if not binding or binding.get("type") != "double_tap":
                continue
            btn_idx = binding.get("button", -1)
            if btn_idx < 0 or btn_idx >= len(self.buttons):
                continue
            if self.buttons[btn_idx].was_double_pressed:
                self._pending_actions.append(name)

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
            if hold_btn.hold_time > 0 and tap_btn.detected_new_press and not tap_btn.was_long_held:
                self._pending_actions.append(name)

    def _detect_overlay_holds(self):
        active = set()
        for name in settings.OVERLAY_ACTIONS:
            binding = settings.get_binding(name)
            if not binding or binding.get("type") != "hold":
                continue
            btn_idx = binding.get("button", -1)
            if 0 <= btn_idx < len(self.buttons) and self.buttons[btn_idx].pressed:
                active.add(name)
        return active
