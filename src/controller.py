import time

import constants as cfg
from gestures import GestureEngine
from inputs import BankButton, MidiSlider
from settings import settings
from user_mode import UserMode

_NAV_ACTIONS = frozenset(
    {
        "nav_next_track",
        "nav_prev_track",
        "nav_next_device",
        "nav_prev_device",
        "nav_next_track_page",
        "nav_prev_track_page",
        "nav_next_send",
        "nav_prev_send",
    }
)
_MODE_SWITCH_ACTIONS = frozenset(
    {
        "mode_focus",
        "mode_four_track",
        "mode_user",
    }
)
_FOCUS_NAV_ACTIONS = frozenset(
    {
        "nav_next_track",
        "nav_prev_track",
        "nav_next_device",
        "nav_prev_device",
    }
)
_FOUR_TRACK_NAV_ACTIONS = frozenset(
    {
        "nav_next_track_page",
        "nav_prev_track_page",
        "nav_next_send",
        "nav_prev_send",
    }
)
_MODE_ACTION_TO_ID = {
    "mode_focus": cfg.MODE_FOCUS,
    "mode_four_track": cfg.MODE_FOUR_TRACK,
    "mode_user": cfg.MODE_USER,
}


class LumaFaderController:
    """Hardware input scanning, gesture detection, and MIDI output."""

    def __init__(self, slider_pins, button_pins, midi_manager, visible_state):
        self.midi = midi_manager
        self.visible_state = visible_state
        self.sliders = [MidiSlider(p, i) for i, p in enumerate(slider_pins)]
        self.buttons = [BankButton(p) for p in button_pins]
        self.gestures = GestureEngine(self.buttons)
        self.user_mode = UserMode(self.sliders, self.buttons)
        self.channel = settings.get_midi_channel()
        self.config_mode = False
        self._last_mode = visible_state.mode_id
        self._overlay_remotes_5_8 = False
        self._overlay_sends = False
        self._overlay_utility = False
        self._fine_active_last = False
        n = len(self.sliders)
        self._last_sent_by_bank = {
            "default": [0] * n,
            "remotes_5_8": [0] * n,
            "sends": [0] * n,
            "utility": [0] * n,
        }
        self._last_sent_position = self._last_sent_by_bank["default"]
        self._fine_phys_baseline = [0] * n
        self._fine_sent_baseline = [0] * len(self.sliders)
        self._last_fine_step_time = [0.0] * len(self.sliders)
        self._fine_pickup = [False] * n
        self._fine_pickup_anchor = [0] * n
        self._fine_pickup_sent = [0] * n
        self._nav_pickup = [False] * n
        self._nav_pickup_anchor = [0] * n

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
        pending_actions = self.gestures.update()
        self._apply_mode_switches(pending_actions)

        mode_id = self.visible_state.mode_id
        if mode_id != self._last_mode:
            if mode_id == cfg.MODE_USER:
                self.user_mode.reset_on_mode_enter()
            self._last_mode = mode_id

        non_mode = [
            name for name in pending_actions if name not in _MODE_SWITCH_ACTIONS
        ]

        if mode_id == cfg.MODE_USER:
            self._process_user_mode(non_mode)
            return

        self._update_fine_state()
        self._send_gesture_actions(non_mode)
        self._update_overlay_state()
        self._send_fader_positions()

    def _apply_mode_switches(self, actions):
        """Firmware owns mode; Bitwig SysEx is optional sync only."""
        pressed_count = sum(1 for button in self.buttons if button.pressed)
        for name in actions:
            if name not in _MODE_SWITCH_ACTIONS:
                continue
            if pressed_count > 1:
                continue
            new_id = _MODE_ACTION_TO_ID.get(name)
            if new_id is None:
                continue
            if new_id != self.visible_state.mode_id:
                self.visible_state.mode_id = new_id
                self.visible_state.overlay_id = 0
                self._release_overlay_ccs()
            self.midi.send_action_pulse(settings.get_action_cc(name), self.channel)

    def _process_user_mode(self, pending_actions):
        user_actions = []
        other_actions = []
        for name in pending_actions:
            if self.user_mode.is_user_nav_action(name):
                user_actions.append(name)
            else:
                other_actions.append(name)

        self.user_mode.handle_actions(user_actions)
        self.user_mode.update_view_from_buttons()

        self._update_fine_state_user()
        self._send_gesture_actions(other_actions)
        self.user_mode.process_faders(self.midi, self._fine_active())

    def _update_fine_state_user(self):
        fine = self._fine_active()
        if fine == self._fine_active_last:
            return
        self._fine_active_last = fine
        if fine:
            self.user_mode.on_fine_press()
        else:
            self.user_mode.on_fine_release()

    def _send_gesture_actions(self, actions):
        """Momentary ACTION_CC pulses for chords / double-taps (not overlays)."""
        if not actions:
            return

        pressed_count = sum(1 for button in self.buttons if button.pressed)
        mode_id = self.visible_state.mode_id
        if mode_id == cfg.MODE_FOUR_TRACK:
            allowed_nav = _FOUR_TRACK_NAV_ACTIONS
        elif mode_id == cfg.MODE_USER:
            allowed_nav = frozenset()
        else:
            allowed_nav = _FOCUS_NAV_ACTIONS

        if any(name in _NAV_ACTIONS for name in actions):
            self._arm_nav_pickup()

        for name in actions:
            if settings.is_overlay_action(name):
                continue
            if name in _MODE_SWITCH_ACTIONS:
                continue
            if name in _NAV_ACTIONS and name not in allowed_nav:
                continue
            if self.user_mode.is_user_nav_action(name):
                continue
            self.midi.send_action_pulse(settings.get_action_cc(name), self.channel)

    def _release_overlay_ccs(self):
        """Clear overlay held CCs before a mode-switch pulse (same physical buttons)."""
        if self._overlay_remotes_5_8:
            self._overlay_remotes_5_8 = False
            self.midi.send_cc_held(
                settings.get_action_cc("overlay_1"), False, self.channel
            )
        if self._overlay_sends:
            self._overlay_sends = False
            self.midi.send_cc_held(
                settings.get_action_cc("overlay_2"), False, self.channel
            )
        if self._overlay_utility:
            self._overlay_utility = False
            self.midi.send_cc_held(
                settings.get_action_cc("overlay_3"), False, self.channel
            )

    def _arm_nav_pickup(self):
        """Do not emit fader CC until moved — nav chords often hold overlay buttons."""
        for i, slider in enumerate(self.sliders):
            self._nav_pickup[i] = True
            self._nav_pickup_anchor[i] = slider.physical_position

    def _nav_pickup_blocks_send(self, index, slider):
        if not self._nav_pickup[index]:
            return False
        if (
            abs(slider.physical_position - self._nav_pickup_anchor[index])
            >= cfg.NAV_PICKUP_SYNC_THRESHOLD_CC
        ):
            self._nav_pickup[index] = False
            return False
        return True

    def _update_overlay_state(self):
        remotes_5_8 = self._overlay_remotes_5_8_active()
        if remotes_5_8 != self._overlay_remotes_5_8:
            self._overlay_remotes_5_8 = remotes_5_8
            self._on_overlay_bank_edge()
            self.midi.send_cc_held(
                settings.get_action_cc("overlay_1"),
                remotes_5_8,
                self.channel,
            )
            if self._fine_active():
                self._refresh_fine_baselines()

        sends = self._overlay_sends_active()
        if sends != self._overlay_sends:
            self._overlay_sends = sends
            self._on_overlay_bank_edge()
            self.midi.send_cc_held(
                settings.get_action_cc("overlay_2"),
                sends,
                self.channel,
            )
            if self._fine_active():
                self._refresh_fine_baselines()

        utility = self._overlay_utility_active()
        if utility != self._overlay_utility:
            self._overlay_utility = utility
            self._on_overlay_bank_edge()
            self.midi.send_cc_held(
                settings.get_action_cc("overlay_3"),
                utility,
                self.channel,
            )
            if self._fine_active():
                self._refresh_fine_baselines()

    def _fine_active(self):
        fine_btn = settings.get_fine_modifier_button()
        if 0 <= fine_btn < len(self.buttons):
            return self.buttons[fine_btn].pressed
        return False

    def _refresh_fine_baselines(self):
        for i, slider in enumerate(self.sliders):
            self._fine_phys_baseline[i] = slider.physical_position
            self._fine_sent_baseline[i] = self._last_sent_position[i]
            self._last_fine_step_time[i] = 0.0

    def _on_overlay_bank_edge(self):
        """Switch CC bank; align dedup to physical so we do not emit CC on button edge."""
        self._last_sent_position = self._last_sent_by_bank[self._fader_cc_bank()]
        for i, slider in enumerate(self.sliders):
            self._last_sent_position[i] = slider.physical_position

    def _update_fine_state(self):
        fine = self._fine_active()
        if fine == self._fine_active_last:
            return
        self.midi.send_cc_held(
            settings.get_action_cc("fine_modifier"),
            fine,
            self.channel,
        )
        self._fine_active_last = fine
        if fine:
            for i in range(len(self.sliders)):
                self._fine_pickup[i] = False
            self._refresh_fine_baselines()
        else:
            self._on_fine_release()

    def _on_fine_release(self):
        """Continue from fine-tuned MIDI; map fader motion as delta from release anchor."""
        for i, slider in enumerate(self.sliders):
            self._fine_pickup[i] = True
            self._fine_pickup_anchor[i] = slider.physical_position
            self._fine_pickup_sent[i] = self._last_sent_position[i]

    def _fine_pickup_position(self, index, slider):
        delta = slider.physical_position - self._fine_pickup_anchor[index]
        target = self._fine_pickup_sent[index] + delta
        return max(cfg.MIN_CC_VALUE, min(cfg.MAX_CC_VALUE, target))

    def _maybe_end_fine_pickup(self, index, slider):
        if not self._fine_pickup[index]:
            return
        pos = self._fine_pickup_position(index, slider)
        if abs(slider.physical_position - pos) <= cfg.FINE_PICKUP_SYNC_THRESHOLD_CC:
            self._fine_pickup[index] = False

    def _fine_cc_increment(self):
        """Max absolute CC change per fine tick (smaller FINE_SCALE = smaller steps)."""
        scale = settings.get_fine_scale()
        if scale <= 0:
            return 1
        return max(1, int(round(1.0 / scale)))

    def _fine_target_position(self, index, slider):
        """Map physical travel since fine was pressed to a scaled MIDI target."""
        scale = settings.get_fine_scale()
        delta_phys = slider.physical_position - self._fine_phys_baseline[index]
        scaled = int(round(delta_phys * scale))
        target = self._fine_sent_baseline[index] + scaled
        return max(cfg.MIN_CC_VALUE, min(cfg.MAX_CC_VALUE, target))

    def _overlay_remotes_5_8_active(self):
        """Focus overlay 1: button 4 held → faders use FADER_CC_OVERLAY (remotes 5–8)."""
        return "overlay_1" in self.gestures.active_overlays

    def _overlay_sends_active(self):
        """Focus overlay 2: button 3 held → faders use FADER_CC_SENDS (sends 1–4)."""
        return "overlay_2" in self.gestures.active_overlays

    def _overlay_utility_active(self):
        """Focus overlay 3: button 2 held → last-touched / pan / volume."""
        return "overlay_3" in self.gestures.active_overlays

    def _fader_cc_bank(self):
        if self._overlay_remotes_5_8_active():
            return "remotes_5_8"
        if self._overlay_sends_active():
            return "sends"
        if self._overlay_utility_active():
            return "utility"
        return "default"

    def _send_fader_positions(self):
        """Send absolute fader CCs; fine mode scales physical travel and creeps in small steps."""
        bank = self._fader_cc_bank()
        fine = self._fine_active()
        fine_inc = self._fine_cc_increment()
        now = time.monotonic()

        for i, slider in enumerate(self.sliders):
            if self.visible_state.fader_mode[i] == cfg.FADER_MODE_DIM:
                slider.consume_delta()
                continue

            slider.consume_delta()

            if fine:
                if now - self._last_fine_step_time[i] < cfg.FINE_STEP_INTERVAL_S:
                    continue
                target = self._fine_target_position(i, slider)
                last = self._last_sent_position[i]
                if target > last:
                    pos = min(target, last + fine_inc)
                elif target < last:
                    pos = max(target, last - fine_inc)
                else:
                    continue
                self._last_sent_position[i] = pos
                self._last_fine_step_time[i] = now
            else:
                if self._nav_pickup_blocks_send(i, slider):
                    continue
                if self._fine_pickup[i]:
                    pos = self._fine_pickup_position(i, slider)
                    self._maybe_end_fine_pickup(i, slider)
                else:
                    pos = slider.physical_position
                if pos == self._last_sent_position[i]:
                    continue
                self._last_sent_position[i] = pos

            cc = settings.get_fader_cc(i, bank)
            self.midi.send_cc_value(cc, pos, self.channel)
