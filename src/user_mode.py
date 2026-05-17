"""User workspace (mode 3): DAW-agnostic CC grid with per-bank MIDI channels and pickup."""

import time

import constants as cfg
import pickup
from settings import settings


_USER_NAV_ACTIONS = frozenset(
    {
        "nav_user_bank_down",
        "nav_user_bank_up",
        "nav_user_page_up",
        "nav_user_page_down",
    }
)


class UserMode:
    """Bank/page/view state, pickup CC output, and local LED feedback hints."""

    def __init__(self, sliders, buttons):
        self.sliders = sliders
        self.buttons = buttons
        self.midi = None
        self.bank = 1
        self.page = 1
        self.view = 0
        self._pages_per_bank = [1, 1, 1, 1]
        self._slot_key = None
        self._last_sent = {}
        self._pickup_crossing = [-1] * 4
        self._pickup_crossed = [False] * 4
        self._awaiting_zero_pickup = [False] * 4
        self._nav_flash_kind = None
        self._nav_flash_bank = 1
        self._nav_flash_page = 1
        self._nav_flash_until = 0.0
        self._nav_reject_edge = cfg.EDGE_NONE
        self._nav_reject_until = 0.0
        self._fine_phys_baseline = [0] * 4
        self._fine_sent_baseline = [0] * 4
        self._last_fine_step_time = [0.0] * 4
        self._fine_pickup = [False] * 4
        self._fine_pickup_anchor = [0] * 4
        self._fine_pickup_sent = [0] * 4

    def reset_on_workspace_enter(self):
        self.bank = 1
        self.page = 1
        self.view = 0
        self._slot_key = None
        self._on_enter()

    def _on_enter(self):
        self.view = self._resolve_view_from_buttons()
        self._apply_slot_change()

    def is_user_nav_action(self, name):
        return name in _USER_NAV_ACTIONS

    def handle_actions(self, actions):
        for name in actions:
            if name == "nav_user_bank_down":
                if self.bank > 1:
                    self._set_bank(self.bank - 1)
                else:
                    self._reject(cfg.EDGE_BOTTOM)
            elif name == "nav_user_bank_up":
                if self.bank < 4:
                    self._set_bank(self.bank + 1)
                else:
                    self._reject(cfg.EDGE_TOP)
            elif name == "nav_user_page_up":
                if self.page < 4:
                    self._set_page(self.page + 1)
                else:
                    self._reject(cfg.EDGE_RIGHT)
            elif name == "nav_user_page_down":
                if self.page > 1:
                    self._set_page(self.page - 1)
                else:
                    self._reject(cfg.EDGE_LEFT)
        self.view = self._resolve_view_from_buttons()

    def update_view_from_buttons(self):
        new_view = self._resolve_view_from_buttons()
        if new_view != self.view:
            self.view = new_view
            self._apply_slot_change()

    def _resolve_view_from_buttons(self):
        if len(self.buttons) < 4:
            return 0
        if (
            self.buttons[cfg.BUTTON_4].pressed
            and self.buttons[cfg.BUTTON_4].hold_time >= cfg.OVERLAY_HOLD_DELAY_S
            and not self.buttons[cfg.BUTTON_4].was_double_pressed
        ):
            return 1
        if (
            self.buttons[cfg.BUTTON_3].pressed
            and self.buttons[cfg.BUTTON_3].hold_time >= cfg.OVERLAY_HOLD_DELAY_S
            and not self.buttons[cfg.BUTTON_3].was_double_pressed
        ):
            return 2
        if (
            self.buttons[cfg.BUTTON_2].pressed
            and self.buttons[cfg.BUTTON_2].hold_time >= cfg.OVERLAY_HOLD_DELAY_S
            and not self.buttons[cfg.BUTTON_2].was_double_pressed
        ):
            return 3
        return 0

    def _set_bank(self, bank):
        self._pages_per_bank[self.bank - 1] = self.page
        self.bank = bank
        if settings.get_user_bank_switch_page() == "reset":
            self.page = 1
        else:
            self.page = self._pages_per_bank[self.bank - 1]
        self._flash_bank(self.bank)
        self._apply_slot_change()

    def _set_page(self, page):
        self.page = page
        self._pages_per_bank[self.bank - 1] = page
        self._flash_page(self.page)
        self._apply_slot_change()

    def _flash_bank(self, bank):
        self._nav_flash_kind = "bank"
        self._nav_flash_bank = bank
        self._nav_flash_until = time.monotonic() + cfg.USER_NAV_FLASH_S

    def _flash_page(self, page):
        self._nav_flash_kind = "page"
        self._nav_flash_page = page
        self._nav_flash_until = time.monotonic() + cfg.USER_NAV_FLASH_S

    def _reject(self, edge):
        self._nav_reject_edge = edge
        self._nav_reject_until = time.monotonic() + cfg.NAV_REJECT_FLASH_S

    def _slot_fader_key(self, fader_index):
        return (self.bank, self.page, self.view, fader_index)

    def _get_last_sent(self, fader_index):
        return self._last_sent.get(self._slot_fader_key(fader_index), 0)

    def _set_last_sent(self, fader_index, value):
        self._last_sent[self._slot_fader_key(fader_index)] = value

    def _apply_slot_change(self):
        key = (self.bank, self.page, self.view)
        if key == self._slot_key:
            return
        self._slot_key = key
        for i, slider in enumerate(self.sliders):
            last = self._get_last_sent(i)
            crossing, crossed = pickup.seed_pickup(slider.physical_position, last)
            self._pickup_crossing[i] = crossing
            self._pickup_crossed[i] = crossed
            self._refresh_awaiting_zero_pickup(i, last, crossed, slider.physical_position)
            slider.consume_delta()

    def _refresh_awaiting_zero_pickup(self, fader_index, last_sent, crossed, physical):
        if last_sent != 0:
            self._awaiting_zero_pickup[fader_index] = False
        elif crossed and physical <= cfg.CC_THRESHOLD:
            self._awaiting_zero_pickup[fader_index] = False
        else:
            self._awaiting_zero_pickup[fader_index] = True

    def midi_channel(self):
        return self.bank - 1

    def on_fine_press(self):
        for i, slider in enumerate(self.sliders):
            self._fine_pickup[i] = False
            self._fine_phys_baseline[i] = slider.physical_position
            self._fine_sent_baseline[i] = self._get_last_sent(i)

    def on_fine_release(self):
        for i, slider in enumerate(self.sliders):
            self._fine_pickup[i] = True
            self._fine_pickup_anchor[i] = slider.physical_position
            self._fine_pickup_sent[i] = self._get_last_sent(i)

    def process_faders(self, midi, fine_active):
        channel = self.midi_channel()
        fine_inc = self._fine_increment()
        now = time.monotonic()

        for i, slider in enumerate(self.sliders):
            if not slider.changed:
                slider.consume_delta()
                continue

            slider.consume_delta()
            cc = settings.get_user_cc(self.bank, self.page, self.view, i)
            last = self._get_last_sent(i)

            if fine_active and self._pickup_crossed[i]:
                if now - self._last_fine_step_time[i] < cfg.FINE_STEP_INTERVAL_S:
                    continue
                target = self._fine_target_position(i, slider)
                if target > last:
                    pos = min(target, last + fine_inc)
                elif target < last:
                    pos = max(target, last - fine_inc)
                else:
                    continue
                self._last_fine_step_time[i] = now
            elif self._fine_pickup[i]:
                pos = self._fine_pickup_position(i, slider)
                self._maybe_end_fine_pickup(i, slider)
            else:
                pos = slider.physical_position

            send, crossing, crossed = pickup.should_send_cc(
                pos,
                last,
                self._pickup_crossing[i],
                self._pickup_crossed[i],
            )
            self._pickup_crossing[i] = crossing
            self._pickup_crossed[i] = crossed
            if not send:
                continue

            midi.send_cc_value(cc, pos, channel)
            self._set_last_sent(i, pos)
            if self._awaiting_zero_pickup[i] and pos <= cfg.CC_THRESHOLD:
                self._awaiting_zero_pickup[i] = False
            if self._pickup_crossed[i]:
                self._pickup_crossing[i] = pos

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

    def _fine_target_position(self, index, slider):
        scale = settings.get_fine_scale()
        delta_phys = slider.physical_position - self._fine_phys_baseline[index]
        scaled = int(round(delta_phys * scale))
        target = self._fine_sent_baseline[index] + scaled
        return max(cfg.MIN_CC_VALUE, min(cfg.MAX_CC_VALUE, target))

    def _fine_increment(self):
        scale = settings.get_fine_scale()
        if scale <= 0:
            return 1
        return max(1, int(round(1.0 / scale)))

    def fader_color(self, fader_index):
        return settings.get_user_fader_color(self.bank, self.page)

    def fader_value_for_led(self, fader_index):
        return self._get_last_sent(fader_index)

    def is_picked_up(self, fader_index):
        return self._pickup_crossed[fader_index]

    def needs_zero_pickup_hint(self, fader_index):
        """Bottom LEDs until the user picks up stored value 0 (not merely touches the fader)."""
        return self._awaiting_zero_pickup[fader_index]

    def nav_flash_color(self):
        if self._nav_flash_kind == "bank":
            return settings.get_user_bank_color(self._nav_flash_bank)
        if self._nav_flash_kind == "page":
            return settings.get_user_fader_color(self.bank, self._nav_flash_page)
        return cfg.COLOR_OFF

    def update_led_timers(self, now):
        if self._nav_flash_until and now >= self._nav_flash_until:
            self._nav_flash_until = 0.0
            self._nav_flash_kind = None
        if self._nav_reject_until and now >= self._nav_reject_until:
            self._nav_reject_until = 0.0
            self._nav_reject_edge = cfg.EDGE_NONE

    @property
    def nav_flash_button(self):
        if not self._nav_flash_kind or self._nav_flash_until <= 0:
            return -1
        if self._nav_flash_kind == "bank":
            return self._nav_flash_bank - 1
        return self._nav_flash_page - 1

    @property
    def nav_reject_edge(self):
        if self._nav_reject_until > 0:
            return self._nav_reject_edge
        return cfg.EDGE_NONE
