import constants as cfg


class VisibleState:
    """LED / UI state driven by Bitwig via SysEx. Firmware does not own semantics."""

    def __init__(self):
        self.mode_id = cfg.MODE_FOCUS
        self.overlay_id = 0
        self.remote_scope = 0
        self.fader_mode = [cfg.FADER_MODE_UNIPOLAR] * 4
        self.fader_value = [0, 0, 0, 0]
        self.fader_color = [(64, 64, 64)] * 4
        self.button_color = [(0, 0, 0)] * 4
        self.nav_reject_edge = cfg.EDGE_NONE
        self.nav_reject_until = 0.0

    def apply_visible_state(self, payload):
        if len(payload) < 36:
            return False

        self.mode_id = payload[0]
        self.overlay_id = payload[1]
        self.remote_scope = payload[2]

        offset = 3
        for i in range(4):
            self.fader_mode[i] = payload[offset]
            self.fader_value[i] = payload[offset + 1]
            self.fader_color[i] = (
                payload[offset + 2],
                payload[offset + 3],
                payload[offset + 4],
            )
            offset += 5

        for i in range(4):
            self.button_color[i] = (
                payload[offset],
                payload[offset + 1],
                payload[offset + 2],
            )
            offset += 3

        reject = payload[offset]
        if reject != cfg.EDGE_NONE:
            self.nav_reject_edge = reject
            self.nav_reject_until = 0.0

        return True

    def arm_nav_reject_timer(self, now):
        if self.nav_reject_edge != cfg.EDGE_NONE and self.nav_reject_until == 0.0:
            self.nav_reject_until = now + cfg.NAV_REJECT_FLASH_S

    def clear_nav_reject_if_expired(self, now):
        if self.nav_reject_edge == cfg.EDGE_NONE:
            return
        if self.nav_reject_until == 0.0:
            return
        if now >= self.nav_reject_until:
            self.nav_reject_edge = cfg.EDGE_NONE
            self.nav_reject_until = 0.0
