package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.ColorValue;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.PinnableCursorDevice;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.SettableColorValue;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

import java.util.function.Supplier;

/**
 * Builds and sends visible-state SysEx (0x10), with debounced scheduling.
 */
final class VisibleStatePublisher
{
   /** Runtime LED / mode inputs for one publish cycle. */
   static final class Snapshot
   {
      final int modeId;
      final int ledDisplayKind;
      final boolean editingTrackRemotes;
      final int navRejectEdge;

      Snapshot(
         final int modeId,
         final int ledDisplayKind,
         final boolean editingTrackRemotes,
         final int navRejectEdge)
      {
         this.modeId = modeId;
         this.ledDisplayKind = ledDisplayKind;
         this.editingTrackRemotes = editingTrackRemotes;
         this.navRejectEdge = navRejectEdge;
      }
   }

   private final ControllerHost host;
   private final VisibleStateSysex sysex;
   private final FourTrackViewport fourTrackViewport;
   private final TrackBank flatTrackBank;
   private final TrackBank fourTrackBank;
   private final CursorTrack cursorTrack;
   private final PinnableCursorDevice cursorDevice;
   private final Parameter[] deviceRemoteParameters;
   private final Parameter[] trackRemoteParameters;
   private final Send[] sends;
   private final Parameter lastTouchedParameter;
   private final Parameter trackPan;
   private final Parameter trackVolume;
   private final Runnable syncFourTrackBank;
   private final Supplier<Snapshot> snapshotSupplier;

   private boolean ledUpdatePending;
   private long ledUpdateScheduledAt;
   private long lastFlushLedMs;

   VisibleStatePublisher(
      final ControllerHost host,
      final VisibleStateSysex sysex,
      final FourTrackViewport fourTrackViewport,
      final TrackBank flatTrackBank,
      final TrackBank fourTrackBank,
      final CursorTrack cursorTrack,
      final PinnableCursorDevice cursorDevice,
      final Parameter[] deviceRemoteParameters,
      final Parameter[] trackRemoteParameters,
      final Send[] sends,
      final Parameter lastTouchedParameter,
      final Parameter trackPan,
      final Parameter trackVolume,
      final Runnable syncFourTrackBank,
      final Supplier<Snapshot> snapshotSupplier)
   {
      this.host = host;
      this.sysex = sysex;
      this.fourTrackViewport = fourTrackViewport;
      this.flatTrackBank = flatTrackBank;
      this.fourTrackBank = fourTrackBank;
      this.cursorTrack = cursorTrack;
      this.cursorDevice = cursorDevice;
      this.deviceRemoteParameters = deviceRemoteParameters;
      this.trackRemoteParameters = trackRemoteParameters;
      this.sends = sends;
      this.lastTouchedParameter = lastTouchedParameter;
      this.trackPan = trackPan;
      this.trackVolume = trackVolume;
      this.syncFourTrackBank = syncFourTrackBank;
      this.snapshotSupplier = snapshotSupplier;
   }

   void scheduleUpdate()
   {
      if (ledUpdatePending)
      {
         return;
      }
      ledUpdatePending = true;
      host.scheduleTask(this::runDebouncedUpdate, LumaFaderConstants.LED_UPDATE_DEBOUNCE_MS);
   }

   void flushIfDue()
   {
      final long now = System.currentTimeMillis();
      if (now - lastFlushLedMs >= LumaFaderConstants.FLUSH_LED_INTERVAL_MS)
      {
         lastFlushLedMs = now;
         send(snapshotSupplier.get());
      }
   }

   void send(final Snapshot snapshot)
   {
      if (sysex == null)
      {
         return;
      }

      final int[] faderModes = new int[LumaFaderConstants.FADER_COUNT];
      final int[] faderValues = new int[LumaFaderConstants.FADER_COUNT];
      final int[][] faderColors = new int[LumaFaderConstants.FADER_COUNT][3];
      final int[][] buttonColors = new int[LumaFaderConstants.FADER_COUNT][3];

      if (snapshot.modeId == SysexProtocol.MODE_FOUR_TRACK)
      {
         fourTrackViewport.rebuildVisibleTracks(
            flatTrackBank, LumaFaderConstants.MAIN_TRACK_BANK_SIZE);
         syncFourTrackBank.run();
         if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_FOUR_VOLUME)
         {
            fillFourTrackVolumeFaderState(faderModes, faderValues, faderColors, buttonColors);
         }
         else if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_FOUR_PAN)
         {
            fillFourTrackPanFaderState(faderModes, faderValues, faderColors, buttonColors);
         }
         else
         {
            fillFourTrackSendFaderState(faderModes, faderValues, faderColors, buttonColors);
         }
      }
      else if (snapshot.modeId == SysexProtocol.MODE_USER)
      {
         for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
         }
      }
      else if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_SENDS)
      {
         fillSendFaderState(faderModes, faderValues, faderColors, buttonColors);
      }
      else if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_UTILITY)
      {
         fillUtilityFaderState(faderModes, faderValues, faderColors, buttonColors);
      }
      else
      {
         fillRemoteFaderState(
            snapshot.ledDisplayKind,
            snapshot.editingTrackRemotes,
            faderModes,
            faderValues,
            faderColors,
            buttonColors);
      }

      sysex.send(
         snapshot.modeId,
         sysexOverlayId(snapshot),
         snapshot.editingTrackRemotes
            ? SysexProtocol.REMOTE_SCOPE_TRACK
            : SysexProtocol.REMOTE_SCOPE_DEVICE,
         faderModes,
         faderValues,
         faderColors,
         buttonColors,
         snapshot.navRejectEdge);
   }

   int[] hostMidi7ForDisplayedFaders(final Snapshot snapshot)
   {
      final int[] hostMidi7 = new int[LumaFaderConstants.FADER_COUNT];
      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         hostMidi7[i] = hostMidi7ForDisplayedFader(snapshot, i);
      }
      return hostMidi7;
   }

   private void runDebouncedUpdate()
   {
      final long now = System.currentTimeMillis();
      if (now - ledUpdateScheduledAt < LumaFaderConstants.LED_UPDATE_DEBOUNCE_MS)
      {
         host.scheduleTask(this::runDebouncedUpdate, LumaFaderConstants.LED_UPDATE_DEBOUNCE_MS);
         return;
      }
      ledUpdatePending = false;
      ledUpdateScheduledAt = now;
      send(snapshotSupplier.get());
   }

   private int hostMidi7ForDisplayedFader(final Snapshot snapshot, final int faderIndex)
   {
      if (snapshot.modeId == SysexProtocol.MODE_FOUR_TRACK)
      {
         return hostMidi7ForFourTrackFader(snapshot, faderIndex);
      }

      if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_SENDS)
      {
         final Send send = sends[faderIndex];
         if (!cursorTrack.exists().get()
            || !VisibleStateSysex.isSendSlotDefinedInProject(send))
         {
            return 0;
         }
         return VisibleStateSysex.parameterToMidi7(send.value().get());
      }

      if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_UTILITY)
      {
         if (!cursorTrack.exists().get())
         {
            return 0;
         }
         switch (faderIndex)
         {
            case LumaFaderConstants.UTILITY_LAST_TOUCHED:
               if (!isLastTouchedActive())
               {
                  return 0;
               }
               return VisibleStateSysex.parameterToMidi7(lastTouchedParameter.value().get());

            case LumaFaderConstants.UTILITY_PAN:
               return VisibleStateSysex.parameterToMidi7(trackPan.value().get());

            case LumaFaderConstants.UTILITY_VOLUME:
               return VisibleStateSysex.parameterToMidi7(trackVolume.value().get());

            default:
               return 0;
         }
      }

      if (!isRemoteTargetReady(snapshot.editingTrackRemotes))
      {
         return 0;
      }

      final int remoteOffset =
         snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_REMOTES_5_8 ? 4 : 0;
      final Parameter parameter = remoteParameterAt(snapshot.editingTrackRemotes, remoteOffset + faderIndex);
      if (!VisibleStateSysex.isRemoteSlotActive(
         true,
         parameter.exists().get(),
         parameter.name().get()))
      {
         return 0;
      }
      return VisibleStateSysex.parameterToMidi7(parameter.value().get());
   }

   private int hostMidi7ForFourTrackFader(final Snapshot snapshot, final int slot)
   {
      if (!fourTrackViewport.isSlotActive(slot))
      {
         return 0;
      }

      if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_FOUR_VOLUME)
      {
         final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, slot);
         return VisibleStateSysex.parameterToMidi7(track.volume().value().get());
      }

      if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_FOUR_PAN)
      {
         final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, slot);
         return VisibleStateSysex.parameterToMidi7(track.pan().value().get());
      }

      final Send send = fourTrackViewport.sendAtSlot(flatTrackBank, slot);
      if (send == null || !VisibleStateSysex.isSendSlotDefinedInProject(send))
      {
         return 0;
      }
      if (!VisibleStateSysex.isSendActiveOnTrack(send))
      {
         return 0;
      }
      return VisibleStateSysex.parameterToMidi7(send.value().get());
   }

   private static int sysexOverlayId(final Snapshot snapshot)
   {
      if (snapshot.modeId == SysexProtocol.MODE_FOUR_TRACK)
      {
         if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_FOUR_VOLUME)
         {
            return 1;
         }
         if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_FOUR_PAN)
         {
            return 2;
         }
         return 0;
      }

      if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_REMOTES_5_8)
      {
         return 1;
      }
      if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_SENDS)
      {
         return 2;
      }
      if (snapshot.ledDisplayKind == LumaFaderConstants.DISPLAY_UTILITY)
      {
         return 3;
      }
      return 0;
   }

   private void fillFourTrackSendFaderState(
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int[][] buttonColors)
   {
      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         clearButtonColor(buttonColors, i);

         if (!fourTrackViewport.isSlotActive(i))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, i);
         final Send send = fourTrackViewport.sendAtSlot(flatTrackBank, i);
         if (track == null
            || send == null
            || !VisibleStateSysex.isSendSlotDefinedInProject(send))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final int[] rgb = midiRgbForSendLed(send, track);
         setFaderColor(faderColors, i, rgb);

         if (!VisibleStateSysex.isSendActiveOnTrack(send))
         {
            faderModes[i] = SysexProtocol.FADER_MODE_STANDBY;
            faderValues[i] = 0;
            continue;
         }

         faderModes[i] = SysexProtocol.FADER_MODE_UNIPOLAR;
         faderValues[i] = VisibleStateSysex.parameterToMidi7(send.value().get());
      }
   }

   private void fillFourTrackVolumeFaderState(
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int[][] buttonColors)
   {
      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         clearButtonColor(buttonColors, i);

         if (!fourTrackViewport.isSlotActive(i))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, i);
         final int[] rgb = midiRgbFromColorValue(track.color());
         setFaderColor(faderColors, i, rgb);
         faderModes[i] = SysexProtocol.FADER_MODE_UNIPOLAR;
         faderValues[i] = VisibleStateSysex.parameterToMidi7(track.volume().value().get());
      }
   }

   private void fillFourTrackPanFaderState(
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int[][] buttonColors)
   {
      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         clearButtonColor(buttonColors, i);

         if (!fourTrackViewport.isSlotActive(i))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, i);
         final int[] rgb = midiRgbFromColorValue(track.color());
         setFaderColor(faderColors, i, rgb);
         faderModes[i] = SysexProtocol.FADER_MODE_PAN;
         faderValues[i] = VisibleStateSysex.parameterToMidi7(track.pan().value().get());
      }
   }

   private void fillUtilityFaderState(
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int[][] buttonColors)
   {
      final boolean trackPresent = cursorTrack.exists().get();

      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         clearButtonColor(buttonColors, i);

         if (i == LumaFaderConstants.UTILITY_RESERVED || !trackPresent)
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         if (i == LumaFaderConstants.UTILITY_LAST_TOUCHED)
         {
            faderColors[i][0] = 0;
            faderColors[i][1] = 0;
            faderColors[i][2] = 0;
            faderModes[i] = SysexProtocol.FADER_MODE_RAINBOW;
            if (!isLastTouchedActive())
            {
               faderValues[i] = 0;
            }
            else
            {
               faderValues[i] =
                  VisibleStateSysex.parameterToMidi7(lastTouchedParameter.value().get());
            }
            continue;
         }

         final int[] rgb =
            i == LumaFaderConstants.UTILITY_PAN || i == LumaFaderConstants.UTILITY_VOLUME
               ? midiRgbFromColorValue(cursorTrack.color())
               : BitwigRemoteColors.midiRgbForUtilitySlot(i);
         setFaderColor(faderColors, i, rgb);

         if (i == LumaFaderConstants.UTILITY_PAN)
         {
            faderModes[i] = SysexProtocol.FADER_MODE_PAN;
            faderValues[i] = VisibleStateSysex.parameterToMidi7(trackPan.value().get());
         }
         else if (i == LumaFaderConstants.UTILITY_VOLUME)
         {
            faderModes[i] = SysexProtocol.FADER_MODE_UNIPOLAR;
            faderValues[i] = VisibleStateSysex.parameterToMidi7(trackVolume.value().get());
         }
      }
   }

   private void fillSendFaderState(
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int[][] buttonColors)
   {
      final boolean trackPresent = cursorTrack.exists().get();

      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         clearButtonColor(buttonColors, i);

         if (!trackPresent)
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final Send send = sends[i];
         if (!VisibleStateSysex.isSendSlotDefinedInProject(send))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final int[] rgb = midiRgbForSendLed(send, cursorTrack);
         setFaderColor(faderColors, i, rgb);

         if (!VisibleStateSysex.isSendActiveOnTrack(send))
         {
            faderModes[i] = SysexProtocol.FADER_MODE_STANDBY;
            faderValues[i] = 0;
            continue;
         }

         faderModes[i] = SysexProtocol.FADER_MODE_UNIPOLAR;
         faderValues[i] = VisibleStateSysex.parameterToMidi7(send.value().get());
      }
   }

   private void fillRemoteFaderState(
      final int ledDisplayKind,
      final boolean editingTrackRemotes,
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int[][] buttonColors)
   {
      final boolean targetPresent =
         editingTrackRemotes
            ? cursorTrack.exists().get()
            : cursorDevice.exists().get();
      final boolean remotesActive = isRemoteTargetReady(editingTrackRemotes);
      final int remoteOffset =
         ledDisplayKind == LumaFaderConstants.DISPLAY_REMOTES_5_8 ? 4 : 0;

      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         clearButtonColor(buttonColors, i);

         if (!targetPresent)
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final Parameter parameter = remoteParameterAt(editingTrackRemotes, remoteOffset + i);
         final boolean exists = parameter.exists().get();
         final String name = parameter.name().get();
         if (!remotesActive
            || !VisibleStateSysex.isRemoteSlotActive(true, exists, name))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final int[] rgb = BitwigRemoteColors.midiRgbForRemoteSlot(remoteOffset + i);
         setFaderColor(faderColors, i, rgb);

         faderModes[i] = VisibleStateSysex.faderModeForParameter(targetPresent, exists, name);
         faderValues[i] = VisibleStateSysex.parameterToMidi7(parameter.value().get());
      }
   }

   private Parameter remoteParameterAt(final boolean editingTrackRemotes, final int remoteIndex)
   {
      return editingTrackRemotes
         ? trackRemoteParameters[remoteIndex]
         : deviceRemoteParameters[remoteIndex];
   }

   private boolean isRemoteTargetReady(final boolean editingTrackRemotes)
   {
      if (editingTrackRemotes)
      {
         return cursorTrack.exists().get()
            && hasActiveRemoteSlot(trackRemoteParameters);
      }
      return cursorDevice.exists().get()
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

   private static int[] midiRgbForSendLed(final Send send, final Track track)
   {
      final int[] fromSend = midiRgbFromSendColor(send.sendChannelColor());
      if (fromSend[0] > 0 || fromSend[1] > 0 || fromSend[2] > 0)
      {
         return fromSend;
      }
      if (track != null && track.exists().get())
      {
         return midiRgbFromColorValue(track.color());
      }
      return fromSend;
   }

   private static int[] midiRgbFromSendColor(final SettableColorValue color)
   {
      return midiRgbFromColorValue(color);
   }

   private static int[] midiRgbFromColorValue(final ColorValue color)
   {
      return new int[] {
         VisibleStateSysex.clampMidi7((int) Math.round(color.red() * 127)),
         VisibleStateSysex.clampMidi7((int) Math.round(color.green() * 127)),
         VisibleStateSysex.clampMidi7((int) Math.round(color.blue() * 127)),
      };
   }

   private static void clearButtonColor(final int[][] buttonColors, final int index)
   {
      buttonColors[index][0] = 0;
      buttonColors[index][1] = 0;
      buttonColors[index][2] = 0;
   }

   private static void setFaderColor(final int[][] faderColors, final int index, final int[] rgb)
   {
      faderColors[index][0] = rgb[0];
      faderColors[index][1] = rgb[1];
      faderColors[index][2] = rgb[2];
   }
}
