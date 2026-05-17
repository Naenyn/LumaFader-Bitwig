import math
import random
import time

import constants as cfg
from settings import settings


class LedRenderer:
    """Renders VisibleState onto the fader strip and button LEDs."""

    @staticmethod
    def _color_from_midi7(color):
        """SysEx carries RGB in 0–127; NeoPixels expect 0–255."""
        return tuple((c * 255 + 63) // 127 for c in color)

    def __init__(self, hardware, visible_state):
        self.hw = hardware
        self.state = visible_state
        self._rainbow_effect = cfg.RAINBOW_EFFECT_SOLID

    def render(self, now, controller):
        self.state.arm_nav_reject_timer(now)
        self.state.clear_nav_reject_if_expired(now)
        self.hw.clear()

        if self.state.mode_id == cfg.MODE_USER:
            controller.user_mode.update_led_timers(now)
            self._render_user_mode(now, controller)
        else:
            self._maybe_pick_rainbow_effect(controller.buttons)
            self._apply_rainbow_effect_from_fader_b(controller.buttons, controller)
            for i in range(4):
                self._render_fader(i, now)
            self._render_button_leds(controller.buttons)
            if self.state.nav_reject_edge != cfg.EDGE_NONE:
                self._apply_nav_reject_flash(self.state.nav_reject_edge)

        self._render_mode_indicator()
        self.hw.show()

    def _render_user_mode(self, now, controller):
        user = controller.user_mode
        fine_btn = settings.get_fine_modifier_button()

        for i in range(4):
            color = user.fader_color(i)
            value = user.fader_value_for_led(i)
            if not user.is_picked_up(i):
                color = tuple(int(c * 0.2) for c in color)
            self._render_fader_user(i, color, value, user.needs_zero_pickup_hint(i))

        for i, button in enumerate(controller.buttons):
            idx = self.hw.button_indices[i]
            if button.pressed and i == fine_btn:
                self.hw.pixels[idx] = cfg.BUTTON_LED_FINE
            elif user.nav_flash_button == i:
                self.hw.pixels[idx] = user.nav_flash_color()
            else:
                self.hw.pixels[idx] = cfg.COLOR_OFF

        edge = user.nav_reject_edge
        if edge != cfg.EDGE_NONE:
            self._apply_nav_reject_flash(edge)

    def _render_fader_user(self, fader_idx, color, value, zero_pickup_hint):
        pixels = self.hw.slider_indices[fader_idx]
        if zero_pickup_hint:
            for i, pix in enumerate(pixels):
                self.hw.pixels[pix] = color if i < 2 else cfg.COLOR_OFF
            return
        self._render_fader_solid(fader_idx, color, value)

    def _render_fader_solid(self, fader_idx, color, value):
        pixels = self.hw.slider_indices[fader_idx]
        count = len(pixels)
        lit = int((value / 127.0) * count)
        lit = max(0, min(count, lit))
        for i, pix in enumerate(pixels):
            self.hw.pixels[pix] = color if i < lit else cfg.COLOR_OFF

    def _render_button_leds(self, buttons):
        """Solid colors while held; off when released (firmware-local, not SysEx)."""
        fine_btn = settings.get_fine_modifier_button()
        for i, button in enumerate(buttons):
            idx = self.hw.button_indices[i]
            if not button.pressed:
                self.hw.pixels[idx] = cfg.COLOR_OFF
            elif i == fine_btn:
                self.hw.pixels[idx] = cfg.BUTTON_LED_FINE
            elif i == cfg.BUTTON_4:
                self.hw.pixels[idx] = cfg.BUTTON_LED_OVERLAY_1
            elif i == cfg.BUTTON_3:
                self.hw.pixels[idx] = cfg.BUTTON_LED_OVERLAY_2
            elif i == cfg.BUTTON_2:
                self.hw.pixels[idx] = cfg.BUTTON_LED_OVERLAY_3
            else:
                self.hw.pixels[idx] = cfg.COLOR_OFF

    def _render_mode_indicator(self):
        """Pixel 68 above the faders — solid mode color (factory: green in reg mode)."""
        color = cfg.MODE_INDICATOR_COLORS.get(
            self.state.mode_id, cfg.COLOR_OFF
        )
        self.hw.pixels[self.hw.INDICATOR_INDEX] = color

    def _focus_utility_button(self):
        btn = settings.focus_layer_button("utility")
        if btn is None:
            return cfg.BUTTON_2
        return btn

    def _maybe_pick_rainbow_effect(self, buttons):
        """New random effect each time utility layer button is pressed in Focus mode."""
        if self.state.mode_id != cfg.MODE_FOCUS:
            return
        util_btn = self._focus_utility_button()
        if buttons[util_btn].detected_new_press:
            self._rainbow_effect = random.randint(0, cfg.RAINBOW_EFFECT_COUNT - 1)

    def _apply_rainbow_effect_from_fader_b(self, buttons, controller):
        """Hidden: fader B position selects rainbow effect on A (five zones, bottom=0 top=4)."""
        if self.state.mode_id != cfg.MODE_FOCUS:
            return
        util_btn = self._focus_utility_button()
        if not buttons[util_btn].pressed:
            return
        if self.state.fader_mode[cfg.UTILITY_FADER_LAST_TOUCHED] != cfg.FADER_MODE_RAINBOW:
            return

        pos = controller.sliders[cfg.UTILITY_FADER_ANIM_PICK].physical_position
        self._rainbow_effect = min(
            cfg.RAINBOW_EFFECT_COUNT - 1,
            (pos * cfg.RAINBOW_EFFECT_COUNT) // 127,
        )

    @staticmethod
    def _fract(value):
        return value - math.floor(value)

    def _render_fader(self, fader_idx, now):
        pixels = self.hw.slider_indices[fader_idx]
        mode = self.state.fader_mode[fader_idx]
        value = self.state.fader_value[fader_idx]
        color = self._color_from_midi7(self.state.fader_color[fader_idx])

        if mode == cfg.FADER_MODE_RAINBOW:
            self._render_rainbow_fader(pixels, value, now)
            return

        if mode in (cfg.FADER_MODE_DIM, cfg.FADER_MODE_STANDBY):
            dim = tuple(int(c * 0.15) for c in color)
            for pix in pixels:
                self.hw.pixels[pix] = dim
            return

        count = len(pixels)
        lit = int((value / 127.0) * count)
        lit = max(0, min(count, lit))

        if mode == cfg.FADER_MODE_PAN:
            self._render_pan_fader(pixels, value)
            return

        if mode == cfg.FADER_MODE_BIPOLAR:
            center = count // 2
            for i, pix in enumerate(pixels):
                dist = abs(i - center)
                half_span = max(1, count // 2)
                threshold = int((lit / float(count)) * half_span)
                self.hw.pixels[pix] = color if dist < threshold else cfg.COLOR_OFF
            return

        for i, pix in enumerate(pixels):
            self.hw.pixels[pix] = color if i < lit else cfg.COLOR_OFF

    def _render_pan_fader(self, pixels, value):
        """Center-origin pan from Bitwig value: green below center CC, red above."""
        count = len(pixels)
        center_cc = cfg.PAN_CENTER_CC
        split = count // 2
        center_low = split - 1
        center_high = split

        if value < center_cc:
            amount = center_cc - value
            lit = min(split, max(0, int((amount / float(center_cc)) * split)))
            for i, pix in enumerate(pixels):
                if i <= center_low:
                    dist = center_low - i
                    self.hw.pixels[pix] = (
                        cfg.COLOR_PAN_LEFT if dist < lit else cfg.COLOR_OFF
                    )
                else:
                    self.hw.pixels[pix] = cfg.COLOR_OFF
            return

        if value > center_cc:
            amount = value - center_cc
            right_span = count - center_high
            max_amount = 127 - center_cc
            lit = min(right_span, max(0, int((amount / float(max_amount)) * right_span)))
            for i, pix in enumerate(pixels):
                if i >= center_high:
                    dist = i - center_high
                    self.hw.pixels[pix] = (
                        cfg.COLOR_PAN_RIGHT if dist < lit else cfg.COLOR_OFF
                    )
                else:
                    self.hw.pixels[pix] = cfg.COLOR_OFF
            return

        for pix in pixels:
            self.hw.pixels[pix] = cfg.COLOR_OFF

    @staticmethod
    def _hsv_to_rgb(hue, saturation, value):
        hue = LedRenderer._fract(hue)
        hue6 = hue * 6.0
        sector = int(hue6) % 6
        f = hue6 - int(hue6)
        p = value * (1.0 - saturation)
        q = value * (1.0 - saturation * f)
        t = value * (1.0 - saturation * (1.0 - f))
        if sector == 0:
            r, g, b = value, t, p
        elif sector == 1:
            r, g, b = q, value, p
        elif sector == 2:
            r, g, b = p, value, t
        elif sector == 3:
            r, g, b = p, q, value
        elif sector == 4:
            r, g, b = t, p, value
        else:
            r, g, b = value, p, q
        return (
            max(0, min(255, int(r * 255))),
            max(0, min(255, int(g * 255))),
            max(0, min(255, int(b * 255))),
        )

    def _render_rainbow_fader(self, pixels, value, now):
        count = len(pixels)
        if count == 0:
            return

        standby = value <= 0
        if standby:
            lit = count
            brightness = cfg.RAINBOW_STANDBY_BRIGHTNESS
        else:
            lit = max(1, int((value / 127.0) * count))
            lit = min(count, lit)
            brightness = 1.0

        t = now / cfg.RAINBOW_CYCLE_S
        effect = self._rainbow_effect

        for i, pix in enumerate(pixels):
            if i >= lit:
                self.hw.pixels[pix] = cfg.COLOR_OFF
                continue

            pos = i / max(1, count - 1)

            if effect == cfg.RAINBOW_EFFECT_SOLID:
                hue = self._fract(t)
                sat = 1.0
                val = brightness

            elif effect == cfg.RAINBOW_EFFECT_TRAVEL_UP:
                hue = self._fract(t + pos)
                sat = 1.0
                val = brightness

            elif effect == cfg.RAINBOW_EFFECT_TRAVEL_DOWN:
                hue = self._fract(t + (1.0 - pos))
                sat = 1.0
                val = brightness

            elif effect == cfg.RAINBOW_EFFECT_COMET_UP:
                head = self._fract(t * 1.35)
                dist = abs(pos - head)
                if dist > 0.5:
                    dist = 1.0 - dist
                sat = 1.0
                val = brightness * (0.2 + 0.8 * max(0.0, 1.0 - dist * 5.0))
                hue = self._fract(t * 0.6 + pos * 0.4)

            elif effect == cfg.RAINBOW_EFFECT_COMET_DOWN:
                head = self._fract(1.0 - t * 1.35)
                dist = abs(pos - head)
                if dist > 0.5:
                    dist = 1.0 - dist
                sat = 1.0
                val = brightness * (0.2 + 0.8 * max(0.0, 1.0 - dist * 5.0))
                hue = self._fract(t * 0.6 + (1.0 - pos) * 0.4)

            else:
                hue = self._fract(t)
                sat = 1.0
                val = brightness

            self.hw.pixels[pix] = self._hsv_to_rgb(hue, sat, val)

    def _flash_fader_column_red(self, fader_idx):
        """Light every pixel in one fader column (slider + its button)."""
        red = cfg.COLOR_REJECT
        for pix in self.hw.slider_indices[fader_idx]:
            self.hw.pixels[pix] = red
        self.hw.pixels[self.hw.button_indices[fader_idx]] = red

    def _apply_nav_reject_flash(self, edge):
        if edge == cfg.EDGE_LEFT:
            self._flash_fader_column_red(0)
        elif edge == cfg.EDGE_RIGHT:
            self._flash_fader_column_red(3)
        elif edge == cfg.EDGE_TOP:
            self.hw.pixels[self.hw.button_indices[3]] = cfg.COLOR_REJECT
            for pix in self.hw.slider_indices.values():
                self.hw.pixels[pix[-1]] = cfg.COLOR_REJECT
        elif edge == cfg.EDGE_BOTTOM:
            self.hw.pixels[self.hw.button_indices[0]] = cfg.COLOR_REJECT
            for pix in self.hw.slider_indices.values():
                self.hw.pixels[pix[0]] = cfg.COLOR_REJECT
