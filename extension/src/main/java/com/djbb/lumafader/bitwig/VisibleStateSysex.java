package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.Send;

/**
 * Builds and sends Bitwig → LumaFader visible-state SysEx (message type {@code 0x10}).
 */
final class VisibleStateSysex
{
   private static final int MIDI_CC_RANGE = 128;

   private final MidiOut midiOut;

   VisibleStateSysex(final MidiOut midiOut)
   {
      this.midiOut = midiOut;
   }

   void send(
      final int workspaceId,
      final int overlayId,
      final int remoteScope,
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int[][] buttonColors,
      final int navRejectEdge)
   {
      final int bodyLen = 4 + SysexProtocol.VISIBLE_STATE_PAYLOAD_LEN;
      final byte[] data = new byte[2 + bodyLen];
      data[0] = (byte) 0xF0;
      data[bodyLen + 1] = (byte) 0xF7;

      data[1] = (byte) SysexProtocol.MANUFACTURER_ID;
      data[2] = (byte) SysexProtocol.DEVICE_ID;
      data[3] = (byte) SysexProtocol.PROTOCOL_VERSION;
      data[4] = (byte) SysexProtocol.MSG_VISIBLE_STATE;

      int offset = 5;
      data[offset++] = (byte) workspaceId;
      data[offset++] = (byte) overlayId;
      data[offset++] = (byte) remoteScope;

      for (int i = 0; i < 4; i++)
      {
         data[offset++] = (byte) faderModes[i];
         data[offset++] = (byte) clampMidi7(faderValues[i]);
         final int[] rgb = faderColors[i];
         data[offset++] = (byte) clampMidi7(rgb[0]);
         data[offset++] = (byte) clampMidi7(rgb[1]);
         data[offset++] = (byte) clampMidi7(rgb[2]);
      }

      for (int i = 0; i < 4; i++)
      {
         final int[] rgb = buttonColors[i];
         data[offset++] = (byte) clampMidi7(rgb[0]);
         data[offset++] = (byte) clampMidi7(rgb[1]);
         data[offset++] = (byte) clampMidi7(rgb[2]);
      }

      data[offset] = (byte) navRejectEdge;

      // Bitwig requires F0 … F7 framing; firmware strips it before parse_sysex().
      midiOut.sendSysexBytes(data);
   }

   void sendWorkspaceChange(final int workspaceId)
   {
      final byte[] data =
         new byte[] {
            (byte) 0xF0,
            (byte) SysexProtocol.MANUFACTURER_ID,
            (byte) SysexProtocol.DEVICE_ID,
            (byte) SysexProtocol.PROTOCOL_VERSION,
            (byte) SysexProtocol.MSG_WORKSPACE_CHANGE,
            (byte) workspaceId,
            (byte) 0xF7,
         };
      midiOut.sendSysexBytes(data);
   }

   static int parameterToMidi7(final double normalized)
   {
      return clampMidi7((int) Math.round(normalized * (MIDI_CC_RANGE - 1)));
   }

   /**
    * A device-remote slot is active when the cursor device is present, Bitwig reports the
    * parameter as existing, and the slot has a non-blank name (empty slots stay off).
    */
   static boolean isRemoteSlotActive(
      final boolean devicePresent,
      final boolean exists,
      final String name)
   {
      if (!devicePresent || !exists)
      {
         return false;
      }
      if (name == null)
      {
         return false;
      }
      return !name.trim().isEmpty();
   }

   static int faderModeForParameter(
      final boolean devicePresent,
      final boolean exists,
      final String name)
   {
      if (!isRemoteSlotActive(devicePresent, exists, name))
      {
         return SysexProtocol.FADER_MODE_DIM;
      }
      if (name.toLowerCase().contains("pan"))
      {
         return SysexProtocol.FADER_MODE_PAN;
      }
      return SysexProtocol.FADER_MODE_UNIPOLAR;
   }

   /**
    * True when the project defines this send bus (e.g. Send 1–3 in a 3-send project).
    * Undefined slots (e.g. fader D when only 3 sends exist) stay off.
    */
   static boolean isSendSlotDefinedInProject(final Send send)
   {
      if (send == null)
      {
         return false;
      }
      if (send.exists().get())
      {
         return true;
      }
      final String name = send.name().get();
      return name != null && !name.trim().isEmpty();
   }

   /**
    * True when this track is actively using the send (routing exists). Unused-but-defined
    * sends report {@code isEnabled() == false} until first use.
    */
   static boolean isSendActiveOnTrack(final Send send)
   {
      return send != null && send.isEnabled().get();
   }

   static void setInactiveFader(
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int faderIndex)
   {
      faderModes[faderIndex] = SysexProtocol.FADER_MODE_DIM;
      faderValues[faderIndex] = 0;
      faderColors[faderIndex][0] = 0;
      faderColors[faderIndex][1] = 0;
      faderColors[faderIndex][2] = 0;
   }

   static int clampMidi7(final int value)
   {
      return Math.max(0, Math.min(MIDI_CC_RANGE - 1, value));
   }
}
