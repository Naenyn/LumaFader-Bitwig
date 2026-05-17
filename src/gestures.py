import constants as cfg
from settings import settings


class GestureEngine:
    """Detects gestures; returns binding names for the controller to map to ACTION_CC."""

    def __init__(self, buttons):
        self.buttons = buttons
        # Canonical overlay slots: overlay_1, overlay_2, overlay_3 (ACTION_CC keys).
        self.active_overlays = set()
        self._pending_actions = []

    def update(self, mode_id):
        self._pending_actions = []
        self._detect_double_taps()
        self._detect_chords()
        self.active_overlays = self._detect_overlay_holds(mode_id)
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
            # Fire on short tap *release* while hold is still down (not on initial press).
            if (
                hold_btn.pressed
                and tap_btn.detected_new_release
                and not tap_btn.was_long_held
                and tap_btn.last_press_duration <= cfg.CHORD_TAP_MAX_S
            ):
                self._pending_actions.append(name)

    def _detect_overlay_holds(self, mode_id):
        """Hold overlays after a short press — bindings are per mode."""
        active = set()
        binding_keys = settings.overlay_binding_keys_for_mode(mode_id)
        for slot, binding_key in enumerate(binding_keys):
            binding = settings.get_binding(binding_key)
            if not binding or binding.get("type") != "hold":
                continue
            btn_idx = binding.get("button", -1)
            if btn_idx < 0 or btn_idx >= len(self.buttons):
                continue
            button = self.buttons[btn_idx]
            if button.was_double_pressed:
                continue
            if button.pressed and button.hold_time >= cfg.OVERLAY_HOLD_DELAY_S:
                active.add(settings.OVERLAY_CC_ACTIONS[slot])
        return active
