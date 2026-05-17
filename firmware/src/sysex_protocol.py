import constants as cfg


def parse_sysex(data, visible_state):
    """
    Parse an incoming SysEx byte sequence (without F0/F7 framing).
    Returns True if a message was handled.
    """
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

    if msg_type == cfg.MSG_WORKSPACE_CHANGE and len(payload) >= 1:
        visible_state.workspace_id = payload[0]
        return True

    if msg_type == cfg.MSG_SCOPE_CHANGE and len(payload) >= 1:
        visible_state.remote_scope = payload[0]
        return True

    return False
