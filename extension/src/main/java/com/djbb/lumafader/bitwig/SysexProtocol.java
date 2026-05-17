package com.djbb.lumafader.bitwig;

/** Matches firmware {@code constants.py} / {@code sysex_protocol.py}. */
final class SysexProtocol
{
   static final int MANUFACTURER_ID = 0x7D;
   static final int DEVICE_ID = 0x01;
   static final int PROTOCOL_VERSION = 0x01;

   static final int MSG_VISIBLE_STATE = 0x10;
   static final int MSG_WORKSPACE_CHANGE = 0x11;
   static final int MSG_SCOPE_CHANGE = 0x12;

   static final int WORKSPACE_FOCUS = 0;
   static final int WORKSPACE_FOUR_TRACK = 1;
   static final int WORKSPACE_USER = 2;
   static final int REMOTE_SCOPE_DEVICE = 0;
   static final int REMOTE_SCOPE_TRACK = 1;

   static final int FADER_MODE_UNIPOLAR = 0;
   static final int FADER_MODE_BIPOLAR = 1;
   static final int FADER_MODE_DIM = 2;
   /** Send exists in project but not enabled on track yet — dim LED, motion allowed. */
   static final int FADER_MODE_STANDBY = 3;
   /** Pan: firmware renders green (CC &lt; center) / red (CC &gt; center) from strip center. */
   static final int FADER_MODE_PAN = 4;
   /** Last-touched utility fader: rainbow animation runs on the controller. */
   static final int FADER_MODE_RAINBOW = 5;

   static final int PAN_CENTER_CC = 64;
   /** Ignore pan MIDI when both hardware and host are near center (re-center latch). */
   static final int PAN_DEADZONE_CC = 4;

   static final int EDGE_NONE = 0;
   static final int EDGE_LEFT = 1;
   static final int EDGE_RIGHT = 2;
   static final int EDGE_TOP = 3;
   static final int EDGE_BOTTOM = 4;

   static final int VISIBLE_STATE_PAYLOAD_LEN = 36;

   private SysexProtocol()
   {
   }
}
