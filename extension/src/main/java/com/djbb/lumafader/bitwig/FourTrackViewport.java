package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

import java.util.ArrayList;
import java.util.List;

/**
 * Four-Track mode viewport: visible mixer tracks in quantized pages of four,
 * plus a shared send index across the page. Does not follow Bitwig selection after init.
 */
final class FourTrackViewport
{
   static final int SLOTS_PER_PAGE = 4;

   private final List<Integer> visibleBankIndices = new ArrayList<>();
   private int pageStart;
   private int sendIndex;

   int getSendIndex()
   {
      return sendIndex;
   }

   int getPageStart()
   {
      return pageStart;
   }

   int getVisibleTrackCount()
   {
      return visibleBankIndices.size();
   }

   void rebuildVisibleTracks(final TrackBank bank, final int bankScanSize)
   {
      visibleBankIndices.clear();
      for (int i = 0; i < bankScanSize; i++)
      {
         final Track track = BitwigChannels.trackAt(bank, i);
         if (!track.exists().get())
         {
            continue;
         }
         if (!track.isActivated().get())
         {
            continue;
         }
         visibleBankIndices.add(i);
      }
      clampPageStart();
   }

   /**
    * Align the page so {@code cursorTrack} (if present in the visible list) lies on this page.
    */
   void initPageAroundCursor(final TrackBank bank, final CursorTrack cursorTrack, final int bankScanSize)
   {
      rebuildVisibleTracks(bank, bankScanSize);
      pageStart = 0;
      sendIndex = 0;

      if (!cursorTrack.exists().get() || visibleBankIndices.isEmpty())
      {
         return;
      }

      final int cursorPosition = cursorTrack.position().get();
      int listIndex = -1;
      for (int i = 0; i < visibleBankIndices.size(); i++)
      {
         final Track track = BitwigChannels.trackAt(bank, visibleBankIndices.get(i));
         if (track.position().get() == cursorPosition)
         {
            listIndex = i;
            break;
         }
      }

      if (listIndex < 0)
      {
         return;
      }

      pageStart = (listIndex / SLOTS_PER_PAGE) * SLOTS_PER_PAGE;
      clampPageStart();
   }

   /**
    * @return true if the page moved; false if rejected (caller should flash top/bottom)
    */
   boolean navigatePage(final int direction)
   {
      if (visibleBankIndices.isEmpty())
      {
         return false;
      }

      if (direction < 0)
      {
         if (pageStart == 0)
         {
            return false;
         }
         pageStart = Math.max(0, pageStart - SLOTS_PER_PAGE);
         return true;
      }

      if (pageStart + SLOTS_PER_PAGE >= visibleBankIndices.size())
      {
         return false;
      }

      pageStart += SLOTS_PER_PAGE;
      clampPageStart();
      return true;
   }

   boolean navigateSend(
      final TrackBank bank,
      final int bankScanSize,
      final int bankSendCount,
      final int direction)
   {
      if (direction < 0)
      {
         if (sendIndex == 0)
         {
            return false;
         }
         sendIndex--;
         return true;
      }

      if (sendIndex + 1 >= bankSendCount)
      {
         return false;
      }

      if (!isSendDefinedInProject(bank, bankScanSize, bankSendCount, sendIndex + 1))
      {
         return false;
      }

      sendIndex++;
      return true;
   }

   boolean isSlotActive(final int slot)
   {
      return slot >= 0
         && slot < SLOTS_PER_PAGE
         && pageStart + slot < visibleBankIndices.size();
   }

   int getBankIndexForSlot(final int slot)
   {
      if (!isSlotActive(slot))
      {
         return -1;
      }
      return visibleBankIndices.get(pageStart + slot);
   }

   /**
    * Arranger track index for the first slot on this page (used to scroll a
    * {@link com.bitwig.extension.controller.api.TrackBank} window).
    */
   int scrollPositionForPage(final TrackBank bank)
   {
      if (visibleBankIndices.isEmpty())
      {
         return 0;
      }
      final Track track = BitwigChannels.trackAt(bank, visibleBankIndices.get(pageStart));
      if (!track.exists().get())
      {
         return 0;
      }
      return track.position().get();
   }

   Track trackAtSlot(final TrackBank bank, final int slot)
   {
      final int bankIndex = getBankIndexForSlot(slot);
      if (bankIndex < 0)
      {
         return null;
      }
      return BitwigChannels.trackAt(bank, bankIndex);
   }

   Send sendAtSlot(final TrackBank bank, final int slot)
   {
      final Track track = trackAtSlot(bank, slot);
      if (track == null)
      {
         return null;
      }
      return BitwigChannels.sendAt(track, sendIndex);
   }

   static boolean isSendDefinedInProject(
      final TrackBank bank,
      final int bankScanSize,
      final int bankSendCount,
      final int index)
   {
      if (index < 0 || index >= bankSendCount)
      {
         return false;
      }

      for (int i = 0; i < bankScanSize; i++)
      {
         final Track track = BitwigChannels.trackAt(bank, i);
         if (!track.exists().get())
         {
            continue;
         }
         final Send send = BitwigChannels.sendAt(track, index);
         if (VisibleStateSysex.isSendSlotDefinedInProject(send))
         {
            return true;
         }
      }
      return false;
   }

   private void clampPageStart()
   {
      if (visibleBankIndices.isEmpty())
      {
         pageStart = 0;
         return;
      }

      final int maxStart =
         Math.max(0, ((visibleBankIndices.size() - 1) / SLOTS_PER_PAGE) * SLOTS_PER_PAGE);
      if (pageStart > maxStart)
      {
         pageStart = maxStart;
      }
      if (pageStart < 0)
      {
         pageStart = 0;
      }
   }
}
