package com.djbb.lumafader.bitwig;

/** Shared MIDI CC numbers, counts, and LED display kinds for the LumaFader extension. */
final class LumaFaderConstants
{
   static final int MIDI_CHANNEL = 0;
   static final int[] FADER_CCS_REMOTES_1_4 = {20, 21, 22, 23};
   static final int[] FADER_CCS_REMOTES_5_8 = {24, 25, 26, 27};
   static final int[] FADER_CCS_SENDS = {28, 29, 30, 31};
   static final int[] FADER_CCS_UTILITY = {32, 33, 34, 35};

   static final int OVERLAY_1_ACTION_CC = 50;
   static final int OVERLAY_2_ACTION_CC = 51;
   static final int OVERLAY_3_ACTION_CC = 52;
   static final int FINE_MODIFIER_ACTION_CC = 53;

   static final int NAV_NEXT_TRACK_CC = 40;
   static final int NAV_PREV_TRACK_CC = 41;
   static final int NAV_NEXT_DEVICE_CC = 42;
   static final int NAV_PREV_DEVICE_CC = 43;
   static final int NAV_NEXT_TRACK_PAGE_CC = 44;
   static final int NAV_PREV_TRACK_PAGE_CC = 45;
   static final int NAV_NEXT_SEND_CC = 46;
   static final int NAV_PREV_SEND_CC = 47;

   static final int MODE_FOCUS_ACTION_CC = 60;
   static final int MODE_FOUR_TRACK_ACTION_CC = 61;
   static final int MODE_USER_ACTION_CC = 62;

   static final int UTILITY_LAST_TOUCHED = 0;
   static final int UTILITY_RESERVED = 1;
   static final int UTILITY_PAN = 2;
   static final int UTILITY_VOLUME = 3;

   static final int REMOTE_COUNT = 8;
   static final int FADER_COUNT = 4;
   static final int TRACK_SEND_COUNT = 4;
   static final int FLAT_TRACK_BANK_SEND_COUNT = 16;

   static final int LED_UPDATE_DEBOUNCE_MS = 25;
   static final int DEVICE_SELECT_DELAY_MS = 100;
   static final int DEVICE_SELECT_MAX_ATTEMPTS = 3;
   static final int MAIN_TRACK_BANK_SIZE = 256;
   static final int FOUR_TRACK_BANK_SIZE = 4;
   static final int FOUR_TRACK_SCENE_COUNT = 8;
   static final int TRACK_DEVICE_BANK_SIZE = 64;
   static final int TRACK_DEVICE_BANK_MARK = 16;
   static final int FLUSH_LED_INTERVAL_MS = 100;

   static final int DISPLAY_REMOTES = 0;
   static final int DISPLAY_REMOTES_5_8 = 1;
   static final int DISPLAY_SENDS = 2;
   static final int DISPLAY_UTILITY = 3;
   static final int DISPLAY_FOUR_SENDS = 4;
   static final int DISPLAY_FOUR_VOLUME = 5;
   static final int DISPLAY_FOUR_PAN = 6;

   private LumaFaderConstants()
   {
   }
}
