# Timing
DOUBLE_PRESS_TIME = 0.3
LONG_HOLD_THRESH_S = 0.5
NAV_REJECT_FLASH_S = 0.15

# Fader smoothing (physical ADC)
SLOW_SMOOTHING_FACTOR = 0.2
FAST_SMOOTHING_FACTOR = 0.85
MOVEMENT_THRESHOLD = 1000
MIDDLE_RANGE_START = 30000
MIDDLE_RANGE_END = 40000
MIDDLE_RANGE_SMOOTHING_FACTOR = 0.05

ADAPTIVE_BUFFER_SIZE = 5
ADAPTIVE_STABLE_THRESHOLD_CC = 3
ADAPTIVE_MOVING_THRESHOLD_CC = 1
ADAPTIVE_STABILITY_RANGE = 3
ADAPTIVE_HOLD_DURATION = 1.0
ADAPTIVE_SMOOTHING_FACTOR = 0.3
ADAPTIVE_RAW_TO_CC_DIVISOR = 512

# Fine modifier: max CC step per tick and minimum time between fine MIDI sends.
FINE_STEP_INTERVAL_S = 0.012
# After fine release: no fader CC until the physical fader moves this many steps (pickup guard).
# After fine release: map physical delta from anchor onto last_sent; exit when aligned.
FINE_PICKUP_SYNC_THRESHOLD_CC = 2
# After track/device nav chord: block fader MIDI until physical moves this many steps.
NAV_PICKUP_SYNC_THRESHOLD_CC = 2

MIN_CC_VALUE = 0
MAX_CC_VALUE = 127

# SysEx: non-commercial manufacturer ID + device sub-ID
SYSEX_MANUFACTURER_ID = 0x7D
SYSEX_DEVICE_ID = 0x01
SYSEX_PROTOCOL_VERSION = 0x01

MSG_VISIBLE_STATE = 0x10
MSG_WORKSPACE_CHANGE = 0x11
MSG_SCOPE_CHANGE = 0x12

# Fader LED modes (from Bitwig)
FADER_MODE_UNIPOLAR = 0
FADER_MODE_BIPOLAR = 1
FADER_MODE_DIM = 2
# Defined in project but not yet on this track — dim LED, fader still sends MIDI.
FADER_MODE_STANDBY = 3
# Pan: one-sided fill from center; green below center CC, red above; see led_renderer.
FADER_MODE_PAN = 4

PAN_CENTER_CC = 64

COLOR_PAN_LEFT = (0, 255, 0)
COLOR_PAN_RIGHT = (255, 0, 0)

# Navigation reject edges
EDGE_NONE = 0
EDGE_LEFT = 1
EDGE_RIGHT = 2
EDGE_TOP = 3
EDGE_BOTTOM = 4

# Workspace IDs (in SysEx from Bitwig)
WORKSPACE_FOCUS = 0
WORKSPACE_FOUR_TRACK = 1
WORKSPACE_USER = 2

# Top indicator LED (pixel 68, between faders B and C) — one color per workspace.
WORKSPACE_INDICATOR_COLORS = {
    WORKSPACE_FOCUS: (0, 0, 255),        # mode 1: blue
    WORKSPACE_FOUR_TRACK: (0, 255, 0),   # mode 2: green
    WORKSPACE_USER: (255, 0, 0),         # mode 3: red
}

# Hardware button indices (bottom = 0, top = 3)
BUTTON_1 = 0
BUTTON_2 = 1
BUTTON_3 = 2
BUTTON_4 = 3

# Colors
COLOR_REJECT = (255, 0, 0)
COLOR_OFF = (0, 0, 0)

# Button LEDs while held (NeoPixel 0–3, bottom = button 1).
BUTTON_LED_FINE = (80, 80, 200)       # button 1 — fine modifier
BUTTON_LED_OVERLAY_3 = (255, 0, 0)    # button 2 — utility overlay
BUTTON_LED_OVERLAY_2 = (0, 255, 0)    # button 3 — sends overlay
BUTTON_LED_OVERLAY_1 = (0, 0, 255)    # button 4 — remotes 5–8 overlay
