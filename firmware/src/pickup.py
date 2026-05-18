"""Pickup mode: do not send MIDI until the fader crosses the last value sent (Cherry firmware)."""

import constants as cfg


def should_send_cc(cc_value, last_sent, crossing_cc_value, has_crossed):
    """
    Returns True when a CC value should be transmitted.

    last_sent: last value the host saw for this (cc, channel).
    crossing_cc_value / has_crossed: per-fader state updated on slot change.
    """
    if crossing_cc_value < 0:
        return False, cc_value, False

    if last_sent == cfg.MIN_CC_VALUE and cfg.MIN_CC_VALUE <= cc_value <= 2:
        return True, cc_value, True

    if last_sent == cfg.MAX_CC_VALUE and 125 <= cc_value <= cfg.MAX_CC_VALUE:
        return True, cc_value, True

    if has_crossed:
        return True, cc_value, has_crossed

    if abs(cc_value - last_sent) <= cfg.CC_THRESHOLD:
        return True, cc_value, True

    crossed_from_above = cc_value < last_sent < crossing_cc_value
    crossed_from_below = cc_value > last_sent > crossing_cc_value
    if crossed_from_above or crossed_from_below:
        return True, cc_value, True

    return False, cc_value, False


def seed_pickup(physical_cc, last_sent):
    """Initialize pickup after bank/page/view change."""
    crossing = last_sent
    if abs(physical_cc - last_sent) <= cfg.CC_THRESHOLD:
        return crossing, True
    return crossing, False
