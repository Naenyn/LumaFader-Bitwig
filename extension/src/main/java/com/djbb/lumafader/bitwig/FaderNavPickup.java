package com.djbb.lumafader.bitwig;

/**
 * After track/device navigation, ignore fader MIDI until the physical fader moves
 * enough from its position at nav time — prevents absolute pickup from writing sends
 * or remotes on the newly selected target (common when nav chords hold overlay buttons).
 */
final class FaderNavPickup
{
   private static final int MOVEMENT_THRESHOLD_CC = 2;

   private final boolean[] active = new boolean[4];
   private final int[] anchorMidi7 = new int[4];

   void arm()
   {
      for (int i = 0; i < active.length; i++)
      {
         active[i] = true;
         anchorMidi7[i] = -1;
      }
   }

   void disarmAll()
   {
      for (int i = 0; i < active.length; i++)
      {
         active[i] = false;
         anchorMidi7[i] = -1;
      }
   }

   /**
    * @return true if the caller must not apply this fader value to Bitwig
    */
   boolean suppressApply(final int faderIndex, final int incomingMidi7)
   {
      if (faderIndex < 0 || faderIndex >= active.length || !active[faderIndex])
      {
         return false;
      }

      if (anchorMidi7[faderIndex] < 0)
      {
         anchorMidi7[faderIndex] = incomingMidi7;
         return true;
      }

      if (Math.abs(incomingMidi7 - anchorMidi7[faderIndex]) >= MOVEMENT_THRESHOLD_CC)
      {
         active[faderIndex] = false;
         return false;
      }

      return true;
   }
}
