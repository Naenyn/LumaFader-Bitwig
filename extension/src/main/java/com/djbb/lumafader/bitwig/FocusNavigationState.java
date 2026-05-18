package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.PinnableCursorDevice;

/** Mutable Focus-mode navigation flags shared by navigation and fader handlers. */
final class FocusNavigationState
{
   boolean editingTrackRemotes;
   boolean syncingTrackRemotesUi;
   long trackRemotesEntryStartedMs;

   private PinnableCursorDevice cursorDevice;

   FocusNavigationState()
   {
   }

   void bindCursorDevice(final PinnableCursorDevice device)
   {
      cursorDevice = device;
   }

   boolean cursorDeviceExists()
   {
      return cursorDevice != null && cursorDevice.exists().get();
   }
}
