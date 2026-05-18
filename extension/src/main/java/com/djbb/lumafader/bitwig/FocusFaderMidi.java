package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.Send;

import java.util.function.IntConsumer;

/** Focus mode: absolute fader CC for remotes, sends, and utility. */
final class FocusFaderMidi
{
   private final ControllerHost host;
   private final CursorTrack cursorTrack;
   private final Parameter[] deviceRemoteParameters;
   private final Parameter[] trackRemoteParameters;
   private final Send[] sends;
   private final Parameter lastTouchedParameter;
   private final Parameter trackPan;
   private final Parameter trackVolume;
   private final FaderTakeover faderTakeover;
   private final FaderNavPickup faderNavPickup;
   private final FocusNavigationState navigationState;
   private final Runnable scheduleVisibleStateUpdate;

   FocusFaderMidi(
      final ControllerHost host,
      final CursorTrack cursorTrack,
      final Parameter[] deviceRemoteParameters,
      final Parameter[] trackRemoteParameters,
      final Send[] sends,
      final Parameter lastTouchedParameter,
      final Parameter trackPan,
      final Parameter trackVolume,
      final FaderTakeover faderTakeover,
      final FaderNavPickup faderNavPickup,
      final FocusNavigationState navigationState,
      final Runnable scheduleVisibleStateUpdate)
   {
      this.host = host;
      this.cursorTrack = cursorTrack;
      this.deviceRemoteParameters = deviceRemoteParameters;
      this.trackRemoteParameters = trackRemoteParameters;
      this.sends = sends;
      this.lastTouchedParameter = lastTouchedParameter;
      this.trackPan = trackPan;
      this.trackVolume = trackVolume;
      this.faderTakeover = faderTakeover;
      this.faderNavPickup = faderNavPickup;
      this.navigationState = navigationState;
      this.scheduleVisibleStateUpdate = scheduleVisibleStateUpdate;
   }

   void handleFaderCc(final int cc, final int incomingMidi7, final IntConsumer setLedDisplayKind)
   {
      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         if (cc == LumaFaderConstants.FADER_CCS_UTILITY[i])
         {
            applyUtilityFaderValue(i, incomingMidi7);
            setLedDisplayKind.accept(LumaFaderConstants.DISPLAY_UTILITY);
            return;
         }
      }

      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         if (cc == LumaFaderConstants.FADER_CCS_SENDS[i])
         {
            applySendFaderValue(i, incomingMidi7);
            setLedDisplayKind.accept(LumaFaderConstants.DISPLAY_SENDS);
            return;
         }
      }

      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         if (cc == LumaFaderConstants.FADER_CCS_REMOTES_5_8[i])
         {
            applyRemoteFaderValue(4 + i, incomingMidi7);
            setLedDisplayKind.accept(LumaFaderConstants.DISPLAY_REMOTES_5_8);
            return;
         }
      }

      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         if (cc == LumaFaderConstants.FADER_CCS_REMOTES_1_4[i])
         {
            applyRemoteFaderValue(i, incomingMidi7);
            setLedDisplayKind.accept(LumaFaderConstants.DISPLAY_REMOTES);
            return;
         }
      }
   }

   private void applyRemoteFaderValue(final int remoteIndex, final int incomingMidi7)
   {
      if (!isRemoteTargetReady())
      {
         return;
      }

      final Parameter parameter = remoteParameterAt(remoteIndex);
      if (!VisibleStateSysex.isRemoteSlotActive(
         true,
         parameter.exists().get(),
         parameter.name().get()))
      {
         return;
      }

      final int faderIndex = remoteIndex % LumaFaderConstants.FADER_COUNT;
      if (faderNavPickup.suppressApply(faderIndex, incomingMidi7))
      {
         return;
      }

      final int midi7 = faderTakeover.resolve(faderIndex, incomingMidi7);
      if (midi7 == FaderTakeover.SUPPRESS)
      {
         return;
      }

      parameter.set(midi7, 128);
      scheduleVisibleStateUpdate.run();
   }

   private void applySendFaderValue(final int index, final int incomingMidi7)
   {
      if (!cursorTrack.exists().get())
      {
         return;
      }

      final Send send = sends[index];
      if (!VisibleStateSysex.isSendSlotDefinedInProject(send))
      {
         return;
      }

      if (faderNavPickup.suppressApply(index, incomingMidi7))
      {
         return;
      }

      final int midi7 = faderTakeover.resolve(index, incomingMidi7);
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

   private void applyUtilityFaderValue(final int index, final int incomingMidi7)
   {
      if (index == LumaFaderConstants.UTILITY_RESERVED)
      {
         return;
      }

      if (!cursorTrack.exists().get())
      {
         return;
      }

      if (faderNavPickup.suppressApply(index, incomingMidi7))
      {
         return;
      }

      final int midi7 = faderTakeover.resolve(index, incomingMidi7);
      if (midi7 == FaderTakeover.SUPPRESS)
      {
         return;
      }

      switch (index)
      {
         case LumaFaderConstants.UTILITY_LAST_TOUCHED:
            if (!isLastTouchedActive())
            {
               return;
            }
            lastTouchedParameter.set(midi7, 128);
            break;

         case LumaFaderConstants.UTILITY_PAN:
            PanFaderHelper.applyPanParameter(trackPan, midi7);
            break;

         case LumaFaderConstants.UTILITY_VOLUME:
            trackVolume.set(midi7, 128);
            break;

         default:
            return;
      }

      scheduleVisibleStateUpdate.run();
   }

   private Parameter remoteParameterAt(final int remoteIndex)
   {
      return navigationState.editingTrackRemotes
         ? trackRemoteParameters[remoteIndex]
         : deviceRemoteParameters[remoteIndex];
   }

   private boolean isRemoteTargetReady()
   {
      if (navigationState.editingTrackRemotes)
      {
         return cursorTrack.exists().get()
            && hasActiveRemoteSlot(trackRemoteParameters);
      }
      return navigationState.cursorDeviceExists()
         && hasActiveRemoteSlot(deviceRemoteParameters);
   }

   private static boolean hasActiveRemoteSlot(final Parameter[] parameters)
   {
      for (int i = 0; i < LumaFaderConstants.REMOTE_COUNT; i++)
      {
         final Parameter parameter = parameters[i];
         if (VisibleStateSysex.isRemoteSlotActive(
            true,
            parameter.exists().get(),
            parameter.name().get()))
         {
            return true;
         }
      }
      return false;
   }

   private boolean isLastTouchedActive()
   {
      return VisibleStateSysex.isRemoteSlotActive(
         true,
         lastTouchedParameter.exists().get(),
         lastTouchedParameter.name().get());
   }

}
