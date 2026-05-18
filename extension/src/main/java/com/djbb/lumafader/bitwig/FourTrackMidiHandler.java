package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

import java.util.function.IntConsumer;

/** Four-Track mode: navigation ACTION_CC and absolute fader CC 20–31. */
final class FourTrackMidiHandler
{
   private final ControllerHost host;
   private final FourTrackViewport fourTrackViewport;
   private final TrackBank flatTrackBank;
   private final FaderTakeover faderTakeover;
   private final FaderNavPickup faderNavPickup;
   private final Runnable syncFourTrackBank;
   private final Runnable onNavigationCompleted;
   private final IntConsumer sendNavReject;
   private final Runnable scheduleVisibleStateUpdate;
   private final IntConsumer setLedDisplayKind;

   FourTrackMidiHandler(
      final ControllerHost host,
      final FourTrackViewport fourTrackViewport,
      final TrackBank flatTrackBank,
      final FaderTakeover faderTakeover,
      final FaderNavPickup faderNavPickup,
      final Runnable syncFourTrackBank,
      final Runnable onNavigationCompleted,
      final IntConsumer sendNavReject,
      final Runnable scheduleVisibleStateUpdate,
      final IntConsumer setLedDisplayKind)
   {
      this.host = host;
      this.fourTrackViewport = fourTrackViewport;
      this.flatTrackBank = flatTrackBank;
      this.faderTakeover = faderTakeover;
      this.faderNavPickup = faderNavPickup;
      this.syncFourTrackBank = syncFourTrackBank;
      this.onNavigationCompleted = onNavigationCompleted;
      this.sendNavReject = sendNavReject;
      this.scheduleVisibleStateUpdate = scheduleVisibleStateUpdate;
      this.setLedDisplayKind = setLedDisplayKind;
   }

   boolean handleNavActionCc(final int cc, final int value)
   {
      if (value < 64)
      {
         return cc == LumaFaderConstants.NAV_NEXT_TRACK_PAGE_CC
            || cc == LumaFaderConstants.NAV_PREV_TRACK_PAGE_CC
            || cc == LumaFaderConstants.NAV_NEXT_SEND_CC
            || cc == LumaFaderConstants.NAV_PREV_SEND_CC;
      }

      switch (cc)
      {
         case LumaFaderConstants.NAV_NEXT_TRACK_PAGE_CC:
            if (fourTrackViewport.navigatePage(1))
            {
               syncFourTrackBank.run();
               onNavigationCompleted.run();
            }
            else
            {
               sendNavReject.accept(SysexProtocol.EDGE_BOTTOM);
            }
            return true;

         case LumaFaderConstants.NAV_PREV_TRACK_PAGE_CC:
            if (fourTrackViewport.navigatePage(-1))
            {
               syncFourTrackBank.run();
               onNavigationCompleted.run();
            }
            else
            {
               sendNavReject.accept(SysexProtocol.EDGE_TOP);
            }
            return true;

         case LumaFaderConstants.NAV_NEXT_SEND_CC:
            if (fourTrackViewport.navigateSend(
               flatTrackBank,
               LumaFaderConstants.MAIN_TRACK_BANK_SIZE,
               LumaFaderConstants.FLAT_TRACK_BANK_SEND_COUNT,
               1))
            {
               onNavigationCompleted.run();
            }
            else
            {
               sendNavReject.accept(SysexProtocol.EDGE_RIGHT);
            }
            return true;

         case LumaFaderConstants.NAV_PREV_SEND_CC:
            if (fourTrackViewport.navigateSend(
               flatTrackBank,
               LumaFaderConstants.MAIN_TRACK_BANK_SIZE,
               LumaFaderConstants.FLAT_TRACK_BANK_SEND_COUNT,
               -1))
            {
               onNavigationCompleted.run();
            }
            else
            {
               sendNavReject.accept(SysexProtocol.EDGE_LEFT);
            }
            return true;

         default:
            return false;
      }
   }

   void handleFaderCc(final int cc, final int incomingMidi7)
   {
      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         if (cc == LumaFaderConstants.FADER_CCS_REMOTES_5_8[i])
         {
            applyVolumeFaderValue(i, incomingMidi7);
            setLedDisplayKind.accept(LumaFaderConstants.DISPLAY_FOUR_VOLUME);
            return;
         }
      }

      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         if (cc == LumaFaderConstants.FADER_CCS_SENDS[i])
         {
            applyPanFaderValue(i, incomingMidi7);
            setLedDisplayKind.accept(LumaFaderConstants.DISPLAY_FOUR_PAN);
            return;
         }
      }

      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         if (cc == LumaFaderConstants.FADER_CCS_REMOTES_1_4[i])
         {
            applySendFaderValue(i, incomingMidi7);
            setLedDisplayKind.accept(LumaFaderConstants.DISPLAY_FOUR_SENDS);
            return;
         }
      }
   }

   private void applySendFaderValue(final int slot, final int incomingMidi7)
   {
      final Send send = fourTrackViewport.sendAtSlot(flatTrackBank, slot);
      applySendParameter(slot, send, incomingMidi7);
   }

   private void applySendParameter(
      final int slot,
      final Send send,
      final int incomingMidi7)
   {
      if (send == null || !VisibleStateSysex.isSendSlotDefinedInProject(send))
      {
         return;
      }

      if (faderNavPickup.suppressApply(slot, incomingMidi7))
      {
         return;
      }

      final int midi7 = faderTakeover.resolve(slot, incomingMidi7);
      if (midi7 == FaderTakeover.SUPPRESS)
      {
         return;
      }

      if (!send.isEnabled().get())
      {
         send.isEnabled().set(true);
         host.scheduleTask(
            () -> {
               send.set(midi7, 128);
               scheduleVisibleStateUpdate.run();
            },
            0);
         return;
      }

      send.set(midi7, 128);
      scheduleVisibleStateUpdate.run();
   }

   private void applyVolumeFaderValue(final int slot, final int incomingMidi7)
   {
      final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, slot);
      if (track == null)
      {
         return;
      }

      if (faderNavPickup.suppressApply(slot, incomingMidi7))
      {
         return;
      }

      final int midi7 = faderTakeover.resolve(slot, incomingMidi7);
      if (midi7 == FaderTakeover.SUPPRESS)
      {
         return;
      }

      track.volume().set(midi7, 128);
      scheduleVisibleStateUpdate.run();
   }

   private void applyPanFaderValue(final int slot, final int incomingMidi7)
   {
      final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, slot);
      if (track == null)
      {
         return;
      }

      if (faderNavPickup.suppressApply(slot, incomingMidi7))
      {
         return;
      }

      final int midi7 = faderTakeover.resolve(slot, incomingMidi7);
      if (midi7 == FaderTakeover.SUPPRESS)
      {
         return;
      }

      PanFaderHelper.applyPanParameter(track.pan(), midi7);
      scheduleVisibleStateUpdate.run();
   }
}
