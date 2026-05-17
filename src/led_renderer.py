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

    def render(self, now, buttons):
        self.state.arm_nav_reject_timer(now)
        self.state.clear_nav_reject_if_expired(now)
        self.hw.clear()

        for i in range(4):
            self._render_fader(i)

        self._render_button_leds(buttons)

        if self.state.nav_reject_edge != cfg.EDGE_NONE:
            self._apply_nav_reject_flash(self.state.nav_reject_edge)

        self._render_workspace_indicator()
        self.hw.show()

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

    def _render_workspace_indicator(self):
        """Pixel 68 above the faders — solid workspace color (factory: green in reg mode)."""
        color = cfg.WORKSPACE_INDICATOR_COLORS.get(
            self.state.workspace_id, cfg.COLOR_OFF
        )
        self.hw.pixels[self.hw.INDICATOR_INDEX] = color

    def _render_fader(self, fader_idx):
        pixels = self.hw.slider_indices[fader_idx]
        mode = self.state.fader_mode[fader_idx]
        value = self.state.fader_value[fader_idx]
        color = self._color_from_midi7(self.state.fader_color[fader_idx])

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
