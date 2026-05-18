"""
Serial configuration handler (Web Serial API).
Preserves the stock LumaFader CMD:/RSP: protocol for settings read/write.
"""

import json
import sys
import time
import supervisor

from settings import (
    _migrate_focus_fader_layers,
    _migrate_four_track_fader_layers,
    _migrate_legacy_mode_keys,
    _migrate_legacy_overlay_bindings,
)

SETTINGS_FILE = "settings.json"
CONFIG_MODE_TIMEOUT = 5.0
LEARN_MODE_TIMEOUT = 30.0
SERIAL_PROTOCOL_VERSION = 2


class SerialConfigHandler:
    def __init__(self):
        self._buffer = ""
        self._controller = None
        self._midi = None
        self._config_mode = False
        self._last_ping_time = 0
        self._learn_mode = False
        self._learn_slider_idx = -1
        self._learn_start_time = 0
        self._last_moved_slider = -1
        self._last_slider_values = [0, 0, 0, 0]
        self._last_midi_type = None
        self._last_midi_num = None
        self._last_midi_ch = None

    @property
    def config_mode(self):
        return self._config_mode

    def set_controller(self, controller):
        self._controller = controller

    def set_midi(self, midi):
        self._midi = midi

    def update(self):
        if self._config_mode and (time.monotonic() - self._last_ping_time) > CONFIG_MODE_TIMEOUT:
            self._config_mode = False
            if self._controller:
                self._controller.config_mode = False

        if self._learn_mode and (time.monotonic() - self._learn_start_time) > LEARN_MODE_TIMEOUT:
            timed_out = self._learn_slider_idx
            self._learn_mode = False
            self._learn_slider_idx = -1
            self._respond({"type": "learn_timeout", "slider": timed_out})

        if self._learn_mode and self._midi:
            cc_data = self._midi.receive_cc()
            if cc_data:
                cc_number, channel = cc_data
                self._last_midi_type = "CC"
                self._last_midi_num = cc_number
                self._last_midi_ch = channel
                self._respond({
                    "type": "learned",
                    "slider": self._learn_slider_idx,
                    "cc": cc_number,
                    "channel": channel,
                })
                self._learn_mode = False
                self._learn_slider_idx = -1
        elif not self._learn_mode and self._midi and self._config_mode:
            midi_data = self._midi.receive_cc_or_at()
            if midi_data:
                self._last_midi_type = midi_data[0]
                self._last_midi_num = midi_data[1]
                self._last_midi_ch = midi_data[2]

        while supervisor.runtime.serial_bytes_available:
            try:
                char = sys.stdin.read(1)
                if not char:
                    break
                self._buffer += char
                if char in ("\n", "\r"):
                    line = self._buffer.strip()
                    self._buffer = ""
                    if line.startswith("CMD:"):
                        self._process_command(line[4:])
                        return True
            except Exception:
                self._buffer = ""
        return False

    def _process_command(self, command):
        try:
            if command == "PING":
                self._last_ping_time = time.monotonic()
                self._respond({"status": "ok", "device": "lumafader-bitwig"})

            elif command == "GET_SETTINGS":
                self._send_file_contents(SETTINGS_FILE)

            elif command == "GET_CAPS":
                self._respond({
                    "type": "caps",
                    "protocol_version": SERIAL_PROTOCOL_VERSION,
                    "capabilities": {
                        "bitwig_mode": True,
                        "gesture_bindings": True,
                    },
                })

            elif command == "GET_STATUS":
                self._send_status()

            elif command.startswith("SET_SETTINGS|"):
                self._save_settings(command[13:])

            elif command.startswith("SET_CONFIG_MODE|"):
                mode = command[16:]
                self._config_mode = mode == "1"
                self._last_ping_time = time.monotonic()
                if self._controller:
                    self._controller.config_mode = self._config_mode
                self._respond({"status": "ok", "config_mode": self._config_mode})

            elif command.startswith("START_LEARN|"):
                slider_str = command[12:]
                slider_idx = int(slider_str)
                if 0 <= slider_idx <= 3:
                    if self._midi:
                        self._midi.flush_receive_buffer()
                    self._learn_mode = True
                    self._learn_slider_idx = slider_idx
                    self._learn_start_time = time.monotonic()
                    self._respond({"type": "learn_started", "slider": slider_idx})
                else:
                    self._respond({"error": "Slider index must be 0-3"})

            elif command == "STOP_LEARN":
                was = self._learn_mode
                slider = self._learn_slider_idx
                self._learn_mode = False
                self._learn_slider_idx = -1
                self._respond({"type": "learn_stopped", "was_learning": was, "slider": slider})

            else:
                self._respond({"error": f"Unknown command: {command}"})

        except Exception as e:
            self._respond({"error": str(e)})

    def _respond(self, data):
        print("RSP:" + json.dumps(data) + "\n", end="")

    def _send_file_contents(self, filepath):
        try:
            with open(filepath, "r") as f:
                data = json.load(f)
            _migrate_legacy_mode_keys(data)
            _migrate_legacy_overlay_bindings(data)
            _migrate_focus_fader_layers(data)
            self._respond(data)
        except OSError:
            self._respond({"error": f"File not found: {filepath}"})
        except ValueError as e:
            self._respond({"error": f"Invalid JSON in {filepath}: {e}"})

    def _send_status(self):
        if self._controller is None:
            self._respond({
                "type": "status",
                "ready": False,
            })
            return

        held = [i for i, b in enumerate(self._controller.buttons) if b.pressed]
        slider_values = [s.position for s in self._controller.sliders]

        moved_slider = -1
        max_change = 0
        for i, (new_val, old_val) in enumerate(zip(slider_values, self._last_slider_values)):
            change = abs(new_val - old_val)
            if change > max_change and change >= 3:
                max_change = change
                moved_slider = i
        if moved_slider != -1:
            self._last_moved_slider = moved_slider
        self._last_slider_values = slider_values[:]

        vs = self._controller.visible_state
        self._respond({
            "type": "status",
            "ready": True,
            "held_buttons": held,
            "slider_values": slider_values,
            "last_moved_slider": self._last_moved_slider,
            "mode": vs.mode_id,
            "overlay": vs.overlay_id,
            "remote_scope": vs.remote_scope,
            "config_mode": self._config_mode,
            "last_midi_type": self._last_midi_type,
            "last_midi_num": self._last_midi_num,
            "last_midi_ch": self._last_midi_ch,
        })

    def _save_settings(self, json_str):
        try:
            data = json.loads(json_str)
            _migrate_legacy_mode_keys(data)
            _migrate_legacy_overlay_bindings(data)
            _migrate_focus_fader_layers(data)
            _migrate_four_track_fader_layers(data)
            with open(SETTINGS_FILE, "w") as f:
                json.dump(data, f)
            self._respond({
                "status": "ok",
                "message": "Settings saved. Restart device to apply.",
            })
        except ValueError as e:
            self._respond({"error": f"Invalid JSON: {e}"})
        except OSError as e:
            self._respond({"error": f"Cannot write file: {e}"})


serial_config = SerialConfigHandler()
