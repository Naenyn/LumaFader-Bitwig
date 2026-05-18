package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.Parameter;

/** Pan center deadzone when applying absolute MIDI to Bitwig pan parameters. */
final class PanFaderHelper
{
   private PanFaderHelper()
   {
   }

   static void applyPanParameter(final Parameter pan, final int midi7)
   {
      final int current = VisibleStateSysex.parameterToMidi7(pan.value().get());
      final boolean incomingNearCenter =
         Math.abs(midi7 - SysexProtocol.PAN_CENTER_CC) <= SysexProtocol.PAN_DEADZONE_CC;
      final boolean hostNearCenter =
         Math.abs(current - SysexProtocol.PAN_CENTER_CC) <= SysexProtocol.PAN_DEADZONE_CC;

      if (incomingNearCenter && hostNearCenter)
      {
         if (current != SysexProtocol.PAN_CENTER_CC)
         {
            pan.set(SysexProtocol.PAN_CENTER_CC, 128);
         }
         return;
      }

      pan.set(midi7, 128);
   }
}
