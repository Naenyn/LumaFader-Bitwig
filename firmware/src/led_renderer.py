import time

import constants as cfg


class LedRenderer:
    """Renders VisibleState onto the fader strip and button LEDs."""

    def __init__(self, hardware, visible_state):
        self.hw = hardware
        self.state = visible_state

    def render(self, now):
        self.state.arm_nav_reject_timer(now)
        self.state.clear_nav_reject_if_expired(now)
        self.hw.clear()

        for i in range(4):
            self._render_fader(i)
            idx = self.hw.button_indices[i]
            self.hw.pixels[idx] = self.state.button_color[i]

        if self.state.nav_reject_edge != cfg.EDGE_NONE:
            self._apply_nav_reject_flash(self.state.nav_reject_edge)

        self.hw.show()

    def _render_fader(self, fader_idx):
        pixels = self.hw.slider_indices[fader_idx]
        mode = self.state.fader_mode[fader_idx]
        value = self.state.fader_value[fader_idx]
        color = self.state.fader_color[fader_idx]

        if mode == cfg.FADER_MODE_DIM:
            dim = tuple(int(c * 0.15) for c in color)
            for pix in pixels:
                self.hw.pixels[pix] = dim
            return

        count = len(pixels)
        lit = int((value / 127.0) * count)
        lit = max(0, min(count, lit))

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
