package com.djbb.lumafader.bitwig;

/**
 * Software takeover ramp: after overlay or fine-modifier release, step the applied MIDI
 * value from the current host parameter toward the physical fader instead of jumping.
 * Bitwig's "relative scaling" only applies to hardware-bound controls; we use onMidi.
 */
final class FaderTakeover
{
   /** Return value: do not call Parameter.set for this message. */
   static final int SUPPRESS = -1;

   /** Max change per MIDI message while catching up (similar to fine creep). */
   private static final int MAX_STEP_PER_MESSAGE = 4;

   private final boolean[] active = new boolean[4];
   private final int[] currentMidi7 = new int[4];

   void arm(final int faderCount, final int[] hostValuesMidi7)
   {
      for (int i = 0; i < faderCount; i++)
      {
         active[i] = true;
         currentMidi7[i] = hostValuesMidi7[i];
      }
   }

   void disarmAll()
   {
      for (int i = 0; i < active.length; i++)
      {
         active[i] = false;
      }
   }

   /**
    * @return value to apply, {@link #SUPPRESS} if unchanged, or pass-through incoming when inactive
    */
   int resolve(final int faderIndex, final int incomingMidi7)
   {
      if (!active[faderIndex])
      {
         return incomingMidi7;
      }

      final int current = currentMidi7[faderIndex];
      if (incomingMidi7 == current)
      {
         return SUPPRESS;
      }

      final int diff = incomingMidi7 - current;
      final int step =
         diff > 0
            ? Math.min(diff, MAX_STEP_PER_MESSAGE)
            : Math.max(diff, -MAX_STEP_PER_MESSAGE);
      final int next = VisibleStateSysex.clampMidi7(current + step);
      currentMidi7[faderIndex] = next;

      if (next == incomingMidi7)
      {
         active[faderIndex] = false;
      }

      return next;
   }
}
