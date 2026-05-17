import constants as cfg


def _normalize_sysex_body(data):
    """Strip F0/F7 if present; adafruit_midi usually omits them on SystemExclusive."""
    body = list(data)
    while body and body[0] == 0xF0:
        body.pop(0)
    while body and body[-1] == 0xF7:
        body.pop()
    return body


def parse_sysex(data, visible_state):
    """
    Parse SysEx body: 7D 01 01 <type> <payload…> (no F0/F7).
    Returns True if a message was handled.
    """
    data = _normalize_sysex_body(data)
    if len(data) < 4:
        return False
    if data[0] != cfg.SYSEX_MANUFACTURER_ID:
        return False
    if data[1] != cfg.SYSEX_DEVICE_ID:
        return False
    if data[2] != cfg.SYSEX_PROTOCOL_VERSION:
        return False

    msg_type = data[3]
    payload = data[4:]

    if msg_type == cfg.MSG_VISIBLE_STATE:
        return visible_state.apply_visible_state(payload)

    if msg_type == cfg.MSG_MODE_CHANGE and len(payload) >= 1:
        visible_state.mode_id = payload[0]
        return True

    if msg_type == cfg.MSG_SCOPE_CHANGE and len(payload) >= 1:
        visible_state.remote_scope = payload[0]
        return True

    return False
