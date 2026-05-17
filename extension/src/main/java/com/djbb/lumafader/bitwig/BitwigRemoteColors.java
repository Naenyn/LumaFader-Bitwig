package com.djbb.lumafader.bitwig;

/**
 * Bitwig device-remote slot colors (indices 0–7 → remotes 1–8).
 * The controller API does not expose per-remote {@code ColorValue}; these match the
 * colors shown in Bitwig's remote control strip.
 */
final class BitwigRemoteColors
{
   private static final int REMOTE_COUNT = 8;

   /** Saturated primaries aligned with Bitwig's remote strip (full 7-bit in SysEx). */
   private static final int[][] MIDI_RGB = {
      {127, 0, 0},
      {127, 68, 0},
      {127, 110, 0},
      {80, 127, 0},
      {0, 127, 127},
      {0, 50, 127},
      {90, 0, 127},
      {127, 0, 70},
   };

   private BitwigRemoteColors()
   {
   }

   static int[] midiRgbForRemoteSlot(final int remoteSlotIndex)
   {
      final int idx = Math.max(0, Math.min(REMOTE_COUNT - 1, remoteSlotIndex));
      final int[] rgb = MIDI_RGB[idx];
      return new int[] {rgb[0], rgb[1], rgb[2]};
   }

   /** Utility overlay fader A–D accent colors (last-touched uses Bitwig parameter color). */
   private static final int[][] UTILITY_MIDI_RGB = {
      {100, 100, 127},
      {0, 0, 0},
      {0, 127, 127},
      {0, 127, 40},
   };

   static int[] midiRgbForUtilitySlot(final int utilitySlotIndex)
   {
      final int idx = Math.max(0, Math.min(UTILITY_MIDI_RGB.length - 1, utilitySlotIndex));
      final int[] rgb = UTILITY_MIDI_RGB[idx];
      return new int[] {rgb[0], rgb[1], rgb[2]};
   }

   private static final long RAINBOW_CYCLE_MS = 4000L;

   /** Animated rainbow for the last-touched utility fader (full saturation in SysEx 7-bit RGB). */
   static int[] midiRgbRainbowCycle(final long timeMs)
   {
      final float hue = (timeMs % RAINBOW_CYCLE_MS) / (float) RAINBOW_CYCLE_MS;
      return hsvToMidiRgb(hue, 1f, 1f);
   }

   private static int[] hsvToMidiRgb(final float hue, final float saturation, final float value)
   {
      final int sector = (int) Math.floor(hue * 6f) % 6;
      final float f = hue * 6f - (float) Math.floor(hue * 6f);
      final float p = value * (1f - saturation);
      final float q = value * (1f - saturation * f);
      final float t = value * (1f - saturation * (1f - f));

      float r;
      float g;
      float b;
      switch (sector)
      {
         case 0:
            r = value;
            g = t;
            b = p;
            break;
         case 1:
            r = q;
            g = value;
            b = p;
            break;
         case 2:
            r = p;
            g = value;
            b = t;
            break;
         case 3:
            r = p;
            g = q;
            b = value;
            break;
         case 4:
            r = t;
            g = p;
            b = value;
            break;
         default:
            r = value;
            g = p;
            b = q;
            break;
      }

      return new int[] {
         clampMidi7(Math.round(r * 127f)),
         clampMidi7(Math.round(g * 127f)),
         clampMidi7(Math.round(b * 127f)),
      };
   }

   private static int clampMidi7(final int value)
   {
      return Math.max(0, Math.min(127, value));
   }

}
