import constants as cfg
from settings import FOCUS_FADER_LAYER_IDS, FOCUS_LAYER_HOLD_PRIORITY, settings


class GestureEngine:
    """Detects gestures; returns binding names for the controller to map to ACTION_CC."""

    def __init__(self, buttons):
        self.buttons = buttons
        # overlay_1/2/3 ACTION_CC keys held (Four-Track + Focus extension sync).
        self.active_overlays = set()
        self.active_focus_layer = "remotes_1_4"
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
        if mode_id == cfg.MODE_FOCUS:
            return self._detect_focus_fader_layers()
        return self._detect_four_track_overlay_holds()

    def _button_hold_active(self, btn_idx):
        if btn_idx < 0 or btn_idx >= len(self.buttons):
            return False
        button = self.buttons[btn_idx]
        if button.was_double_pressed:
            return False
        return (
            button.pressed
            and button.hold_time >= cfg.OVERLAY_HOLD_DELAY_S
        )

    def _detect_focus_fader_layers(self):
        """Map held buttons → Focus fader layer; emit matching overlay ACTION_CCs."""
        layer_map = settings.get_focus_fader_layers()
        fine_btn = settings.get_fine_modifier_button()
        held_layers = []
        for layer_id in FOCUS_FADER_LAYER_IDS:
            trigger = layer_map.get(layer_id, "default")
            if trigger == "default":
                continue
            btn_idx = int(trigger)
            if btn_idx == fine_btn:
                continue
            if self._button_hold_active(btn_idx):
                held_layers.append(layer_id)

        active = set()
        for layer_id in held_layers:
            action = settings.focus_layer_overlay_action(layer_id)
            if action:
                active.add(action)

        self.active_focus_layer = "remotes_1_4"
        for layer_id in FOCUS_LAYER_HOLD_PRIORITY:
            if layer_id in held_layers:
                self.active_focus_layer = layer_id
                break
        return active

    def _detect_four_track_overlay_holds(self):
        active = set()
        binding_keys = settings.overlay_binding_keys_for_mode(cfg.MODE_FOUR_TRACK)
        for slot, binding_key in enumerate(binding_keys):
            binding = settings.get_binding(binding_key)
            if not binding or binding.get("type") != "hold":
                continue
            btn_idx = binding.get("button", -1)
            if self._button_hold_active(btn_idx):
                active.add(settings.OVERLAY_CC_ACTIONS[slot])
        return active
