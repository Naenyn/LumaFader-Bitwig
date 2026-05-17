import board
import busio
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

MIDI_AUX_TX_PIN = board.GP16
MIDI_AUX_RX_PIN = board.GP17


class MidiTransport:
    """USB + TRS MIDI I/O for relative CC, notes, gestures, and SysEx."""

    def __init__(self, visible_state):
        self.visible_state = visible_state
        self._sysex_buffer = []
        self._in_sysex = False

        uart = busio.UART(
            MIDI_AUX_TX_PIN,
            MIDI_AUX_RX_PIN,
            baudrate=31250,
            timeout=0.001,
        )
        self.midi = adafruit_midi.MIDI(
            midi_in=usb_midi.ports[0],
            midi_out=usb_midi.ports[1],
            in_channel=0,
            out_channel=0,
        )
        self.trs_midi = adafruit_midi.MIDI(
            midi_in=uart,
            midi_out=uart,
            in_channel=0,
            out_channel=0,
            debug=False,
        )

    def poll_incoming(self):
        for port in (self.midi, self.trs_midi):
            while True:
                msg = port.receive()
                if msg is None:
                    break
                self._handle_incoming(msg)

    def _handle_incoming(self, msg):
        if SystemExclusive is not None and isinstance(msg, SystemExclusive):
            data = msg.data
            if isinstance(data, (bytes, bytearray)):
                data = list(data)
            if data:
                sysex_protocol.parse_sysex(data, self.visible_state)
            return

        raw = getattr(msg, "data", None)
        if raw is not None:
            self._feed_sysex_byte(raw)
            return

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
            sysex_protocol.parse_sysex(self._sysex_buffer, self.visible_state)
        self._sysex_buffer = []

    def send_relative_cc(self, cc_number, delta, channel):
        if delta == 0:
            return
        steps = abs(delta)
        direction = 1 if delta > 0 else -1
        value = 65 if direction > 0 else 63
        msg = ControlChange(cc_number, value, channel=channel)
        for _ in range(steps):
            self.midi.send(msg, channel=channel)
            self.trs_midi.send(msg, channel=channel)

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
        self.trs_midi.send(msg, channel=channel)

    def send_gesture(self, gesture_id, param, gesture_cc, channel):
        self._send_cc_value(gesture_cc, gesture_id, channel)
        self._send_cc_value(gesture_cc + 1, param, channel)

    def send_fine_modifier(self, active, fine_cc, channel):
        self._send_cc_value(fine_cc, 127 if active else 0, channel)

    def _send_cc_value(self, cc_number, value, channel):
        msg = ControlChange(cc_number, max(0, min(127, value)), channel=channel)
        self.midi.send(msg, channel=channel)
        self.trs_midi.send(msg, channel=channel)

    def flush_receive_buffer(self):
        while self.midi.receive() is not None:
            pass
        while self.trs_midi.receive() is not None:
            pass

    def receive_cc(self):
        for port in (self.midi, self.trs_midi):
            msg = port.receive()
            if msg is not None and isinstance(msg, ControlChange):
                return (msg.control, msg.channel + 1)
        return None

    def receive_cc_or_at(self):
        from adafruit_midi.channel_pressure import ChannelPressure

        for port in (self.midi, self.trs_midi):
            msg = port.receive()
            if msg is None:
                continue
            if isinstance(msg, ControlChange):
                return ("CC", msg.control, msg.channel + 1)
            if isinstance(msg, ChannelPressure):
                return ("AT", msg.pressure, msg.channel + 1)
        return None
