import usb_midi
import adafruit_midi
from adafruit_midi.control_change import ControlChange
from adafruit_midi.note_on import NoteOn
from adafruit_midi.note_off import NoteOff

try:
    from adafruit_midi.system_exclusive import SystemExclusive
except ImportError:
    SystemExclusive = None

import sysex_protocol

# Visible-state SysEx is 42 bytes (F0 … 7D 01 01 10 + 36 payload + F7).
# adafruit_midi default in_buf_size=30 cannot fit it and will stall forever.
SYSEX_IN_BUF_SIZE = 64


class MidiManager:
    """USB MIDI only — relative CC, notes, action CCs, and SysEx from Bitwig."""

    def __init__(self, visible_state):
        self.visible_state = visible_state
        self._sysex_buffer = []
        self._in_sysex = False

        self.midi = adafruit_midi.MIDI(
            midi_in=usb_midi.ports[0],
            midi_out=usb_midi.ports[1],
            in_channel=0,
            out_channel=0,
            in_buf_size=SYSEX_IN_BUF_SIZE,
        )

    def poll_incoming(self):
        while True:
            msg = self.midi.receive()
            if msg is None:
                break
            self._handle_incoming(msg)

    def _handle_incoming(self, msg):
        if SystemExclusive is not None and isinstance(msg, SystemExclusive):
            # adafruit_midi splits F0 7D … F7 into manufacturer_id=[7D] and data=[01 01 10 …].
            # parse_sysex expects the full body starting with 7D.
            body = list(msg.manufacturer_id) + list(msg.data)
            if body:
                if sysex_protocol.parse_sysex(body, self.visible_state):
                    self.visible_state.sysex_ok_count += 1
                else:
                    self.visible_state.sysex_fail_count += 1
            return

        raw = getattr(msg, "data", None)
        if raw is not None:
            self._feed_sysex_byte(raw)

    def _feed_sysex_byte(self, value):
        if value == 0xF0:
            self._in_sysex = True
            self._sysex_buffer = []
            return
        if value == 0xF7:
            self._finish_sysex()
            return
        if self._in_sysex:
            self._sysex_buffer.append(value)

    def _finish_sysex(self):
        self._in_sysex = False
        if self._sysex_buffer:
            if sysex_protocol.parse_sysex(self._sysex_buffer, self.visible_state):
                self.visible_state.sysex_ok_count += 1
            else:
                self.visible_state.sysex_fail_count += 1
        self._sysex_buffer = []

    def send_relative_cc(self, cc_number, delta, channel):
        if delta == 0:
            return
        value = 65 if delta > 0 else 63
        msg = ControlChange(cc_number, value, channel=channel)
        for _ in range(abs(delta)):
            self.midi.send(msg, channel=channel)

    def send_scaled_relative_cc(self, cc_number, delta, channel, scale=1.0):
        if delta == 0 or scale <= 0:
            return
        magnitude = max(1, int(round(abs(delta) * scale)))
        signed = magnitude if delta > 0 else -magnitude
        self.send_relative_cc(cc_number, signed, channel)

    def send_button_note(self, note, pressed, channel, velocity=127):
        if pressed:
            msg = NoteOn(note, velocity, channel=channel)
        else:
            msg = NoteOff(note, 0, channel=channel)
        self.midi.send(msg, channel=channel)

    def send_cc_value(self, cc_number, value, channel):
        msg = ControlChange(cc_number, max(0, min(127, value)), channel=channel)
        self.midi.send(msg, channel=channel)

    def send_action_pulse(self, cc_number, channel, velocity=127):
        """Momentary action: peak then release (for Bitwig note-style mapping)."""
        self.send_cc_value(cc_number, velocity, channel)
        self.send_cc_value(cc_number, 0, channel)

    def send_cc_held(self, cc_number, active, channel, on_value=127):
        self.send_cc_value(cc_number, on_value if active else 0, channel)

    def flush_receive_buffer(self):
        while self.midi.receive() is not None:
            pass

    def receive_cc(self):
        msg = self.midi.receive()
        if msg is not None and isinstance(msg, ControlChange):
            return (msg.control, msg.channel + 1)
        return None

    def receive_cc_or_at(self):
        from adafruit_midi.channel_pressure import ChannelPressure

        msg = self.midi.receive()
        if msg is None:
            return None
        if isinstance(msg, ControlChange):
            return ("CC", msg.control, msg.channel + 1)
        if isinstance(msg, ChannelPressure):
            return ("AT", msg.pressure, msg.channel + 1)
        return None


def create_midi_manager(visible_state):
    return MidiManager(visible_state)
