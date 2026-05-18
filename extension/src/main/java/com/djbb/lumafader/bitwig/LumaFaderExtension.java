package com.djbb.lumafader.bitwig;

import com.bitwig.extension.api.util.midi.ShortMidiMessage;
import com.bitwig.extension.callback.BooleanValueChangedCallback;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.callback.StringValueChangedCallback;
import com.bitwig.extension.callback.ShortMidiMessageReceivedCallback;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorDeviceFollowMode;
import com.bitwig.extension.controller.api.Device;
import com.bitwig.extension.controller.api.PinnableCursorDevice;
import com.bitwig.extension.controller.api.DeviceBank;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.LastClickedParameter;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Focus mode: CC 20–23 → remotes 1–4; CC 24–27 → remotes 5–8 (track or device);
 * CC 28–31 → sends 1–4 (overlay 2); CC 32–35 → utility (overlay 3).
 * LED feedback via SysEx visible-state updates.
 */
public class LumaFaderExtension extends ControllerExtension
{
   private ControllerHost host;
   private int modeId = SysexProtocol.MODE_FOCUS;
   private final FourTrackViewport fourTrackViewport = new FourTrackViewport();
   private CursorTrack cursorTrack;
   private TrackBank flatTrackBank;
   private TrackBank fourTrackBank;
   private PinnableCursorDevice cursorDevice;
   private LastClickedParameter lastClicked;
   private Parameter lastTouchedParameter;
   private Parameter trackPan;
   private Parameter trackVolume;
   private DeviceBank trackDeviceBank;
   private DeviceBank siblingDeviceBank;
   private CursorRemoteControlsPage deviceRemoteControlsPage;
   private CursorRemoteControlsPage trackRemoteControlsPage;
   private VisibleStateSysex visibleStateSysex;
   private VisibleStatePublisher visibleStatePublisher;
   private FocusNavigationState focusNavigationState;
   private FocusNavigation focusNavigation;
   private FourTrackMidiHandler fourTrackMidiHandler;
   private FocusFaderMidi focusFaderMidi;
   private final FaderTakeover faderTakeover = new FaderTakeover();
   private final FaderNavPickup faderNavPickup = new FaderNavPickup();
   private final Parameter[] deviceRemoteParameters =
      new Parameter[LumaFaderConstants.REMOTE_COUNT];
   private final Parameter[] trackRemoteParameters =
      new Parameter[LumaFaderConstants.REMOTE_COUNT];
   private final Send[] sends = new Send[LumaFaderConstants.FADER_COUNT];

   private int ledDisplayKind;
   private boolean overlay1Held;
   private boolean overlay2Held;
   private boolean overlay3Held;
   private boolean fineModifierHeld;

   protected LumaFaderExtension(
      final LumaFaderExtensionDefinition definition,
      final ControllerHost host)
   {
      super(definition, host);
   }

   @Override
   public void init()
   {
      host = (ControllerHost) getHost();
      final MidiIn midiIn = host.getMidiInPort(0);
      final MidiOut midiOut = host.getMidiOutPort(0);
      visibleStateSysex = new VisibleStateSysex(midiOut);

      cursorTrack = host.createCursorTrack(
         "lumafader-cursor",
         "LumaFader Cursor",
         LumaFaderConstants.TRACK_SEND_COUNT,
         0,
         true);
      flatTrackBank =
         host.createTrackBank(
            LumaFaderConstants.MAIN_TRACK_BANK_SIZE,
            LumaFaderConstants.FLAT_TRACK_BANK_SEND_COUNT,
            0,
            true);
      fourTrackBank =
         host.createTrackBank(
            LumaFaderConstants.FOUR_TRACK_BANK_SIZE,
            0,
            LumaFaderConstants.FOUR_TRACK_SCENE_COUNT,
            true);
      fourTrackBank.scrollPosition().markInterested();
      markFourTrackBankInterested();
      markFlatTrackBankInterested();
      attachFlatTrackBankObservers();
      cursorDevice = cursorTrack.createCursorDevice(
         "lumafader-device",
         "LumaFader Device",
         LumaFaderConstants.TRACK_SEND_COUNT,
         CursorDeviceFollowMode.FOLLOW_SELECTION);
      focusNavigationState = new FocusNavigationState();
      focusNavigationState.bindCursorDevice(cursorDevice);
      trackDeviceBank =
         cursorTrack.createDeviceBank(LumaFaderConstants.TRACK_DEVICE_BANK_SIZE);
      siblingDeviceBank =
         cursorDevice.createSiblingsDeviceBank(LumaFaderConstants.TRACK_DEVICE_BANK_MARK);
      deviceRemoteControlsPage = cursorDevice.createCursorRemoteControlsPage(
         LumaFaderConstants.REMOTE_COUNT);
      trackRemoteControlsPage =
         cursorTrack.createCursorRemoteControlsPage(
            "lumafader-track-remotes",
            LumaFaderConstants.REMOTE_COUNT,
            "");
      initRemoteParameters();
      initSendParameters();
      initUtilityParameters();
      wireHandlers();

      attachRemoteObservers();
      attachSendObservers();
      attachUtilityObservers();
      attachDeviceObservers();
      attachTrackObservers();

      cursorTrack.hasNext().markInterested();
      cursorTrack.hasPrevious().markInterested();
      cursorDevice.hasNext().markInterested();
      cursorDevice.hasPrevious().markInterested();

      setupMidiInput(midiIn);

      host.scheduleTask(
         () -> focusNavigation.scheduleSelectFirstDeviceOnCursorTrack(),
         LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
      host.scheduleTask(this::sendVisibleState, 200);

      host.println("LumaFader: CC 20–35 via onMidi; takeover ramp on overlay/fine release");
      host.println("Navigation: ACTION_CC 40–47 chord pulses (mode-dependent)");
      host.println("Modes: ACTION_CC 60–62 double-tap");
      host.println("LED feedback: SysEx visible state (0x10)");
   }

   private void wireHandlers()
   {
      visibleStatePublisher =
         new VisibleStatePublisher(
            host,
            visibleStateSysex,
            fourTrackViewport,
            flatTrackBank,
            fourTrackBank,
            cursorTrack,
            cursorDevice,
            deviceRemoteParameters,
            trackRemoteParameters,
            sends,
            lastTouchedParameter,
            trackPan,
            trackVolume,
            this::syncFourTrackBankToViewport,
            this::currentVisibleSnapshot);

      focusNavigation =
         new FocusNavigation(
            host,
            cursorTrack,
            cursorDevice,
            trackDeviceBank,
            siblingDeviceBank,
            deviceRemoteParameters,
            trackRemoteParameters,
            focusNavigationState,
            this::sendNavReject,
            this::onNavigationCompleted,
            this::scheduleVisibleStateUpdate);

      fourTrackMidiHandler =
         new FourTrackMidiHandler(
            host,
            fourTrackViewport,
            flatTrackBank,
            faderTakeover,
            faderNavPickup,
            this::syncFourTrackBankToViewport,
            this::onNavigationCompleted,
            this::sendNavReject,
            this::scheduleVisibleStateUpdate,
            this::setLedDisplayKind);

      focusFaderMidi =
         new FocusFaderMidi(
            host,
            cursorTrack,
            deviceRemoteParameters,
            trackRemoteParameters,
            sends,
            lastTouchedParameter,
            trackPan,
            trackVolume,
            faderTakeover,
            faderNavPickup,
            focusNavigationState,
            this::scheduleVisibleStateUpdate);
   }

   private boolean isFocusMode()
   {
      return modeId == SysexProtocol.MODE_FOCUS;
   }

   private boolean isFourTrackMode()
   {
      return modeId == SysexProtocol.MODE_FOUR_TRACK;
   }

   private boolean isUserMode()
   {
      return modeId == SysexProtocol.MODE_USER;
   }

   private VisibleStatePublisher.Snapshot currentVisibleSnapshot()
   {
      return new VisibleStatePublisher.Snapshot(
         modeId,
         ledDisplayKind,
         focusNavigationState.editingTrackRemotes,
         SysexProtocol.EDGE_NONE);
   }

   private void markFourTrackBankInterested()
   {
      for (int i = 0; i < LumaFaderConstants.FOUR_TRACK_BANK_SIZE; i++)
      {
         BitwigChannels.trackAt(fourTrackBank, i).exists().markInterested();
      }
   }

   private void markFlatTrackBankInterested()
   {
      for (int i = 0; i < LumaFaderConstants.MAIN_TRACK_BANK_SIZE; i++)
      {
         final Track track = BitwigChannels.trackAt(flatTrackBank, i);
         track.exists().markInterested();
         track.isActivated().markInterested();
         track.position().markInterested();
         track.color().markInterested();
         track.volume().markInterested();
         track.pan().markInterested();

         for (int s = 0; s < LumaFaderConstants.FLAT_TRACK_BANK_SEND_COUNT; s++)
         {
            final Send send = BitwigChannels.sendAt(track, s);
            send.exists().markInterested();
            send.name().markInterested();
            send.isEnabled().markInterested();
            send.value().markInterested();
            send.sendChannelColor().markInterested();
         }
      }
   }

   private void attachFlatTrackBankObservers()
   {
      for (int i = 0; i < LumaFaderConstants.MAIN_TRACK_BANK_SIZE; i++)
      {
         final Track track = BitwigChannels.trackAt(flatTrackBank, i);
         track.isActivated().addValueObserver(
            (BooleanValueChangedCallback) activated -> onFlatTrackBankDataChanged());
         track.volume().value().addValueObserver(
            (DoubleValueChangedCallback) value -> onFlatTrackBankDataChanged());
         track.pan().value().addValueObserver(
            (DoubleValueChangedCallback) value -> onFlatTrackBankDataChanged());

         for (int s = 0; s < LumaFaderConstants.FLAT_TRACK_BANK_SEND_COUNT; s++)
         {
            final Send send = BitwigChannels.sendAt(track, s);
            send.value().addValueObserver(
               (DoubleValueChangedCallback) value -> onFlatTrackBankDataChanged());
            send.isEnabled().addValueObserver(
               (BooleanValueChangedCallback) enabled -> onFlatTrackBankDataChanged());
         }
      }
   }

   private void onFlatTrackBankDataChanged()
   {
      if (!isFourTrackMode())
      {
         return;
      }
      fourTrackViewport.rebuildVisibleTracks(
         flatTrackBank, LumaFaderConstants.MAIN_TRACK_BANK_SIZE);
      syncFourTrackBankToViewport();
      scheduleVisibleStateUpdate();
   }

   private void setFourTrackWindowIndication(final boolean enable)
   {
      if (fourTrackBank == null)
      {
         return;
      }
      fourTrackBank.setShouldShowClipLauncherFeedback(enable);
   }

   private void enableFourTrackWindowIndication()
   {
      setFourTrackWindowIndication(true);
      syncFourTrackBankToViewport();
   }

   private void syncFourTrackBankToViewport()
   {
      if (fourTrackBank == null)
      {
         return;
      }
      final int position = fourTrackViewport.scrollPositionForPage(flatTrackBank);
      fourTrackBank.scrollPosition().set(position);
      fourTrackBank.scrollIntoView(position);
   }

   private void initRemoteParameters()
   {
      for (int i = 0; i < LumaFaderConstants.REMOTE_COUNT; i++)
      {
         final Parameter deviceParameter = deviceRemoteControlsPage.getParameter(i);
         deviceRemoteParameters[i] = deviceParameter;
         deviceParameter.markInterested();

         final Parameter trackParameter = trackRemoteControlsPage.getParameter(i);
         trackRemoteParameters[i] = trackParameter;
         trackParameter.markInterested();
      }
   }

   private void initSendParameters()
   {
      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         sends[i] = BitwigChannels.sendAt(cursorTrack, i);
         sends[i].markInterested();
      }
   }

   private void initUtilityParameters()
   {
      lastClicked =
         host.createLastClickedParameter(
            "lumafader-last-touched", "LumaFader Last Touched");
      lastTouchedParameter = lastClicked.parameter();
      lastTouchedParameter.markInterested();
      trackPan = cursorTrack.pan();
      trackVolume = cursorTrack.volume();
      trackPan.markInterested();
      trackVolume.markInterested();
   }

   private void setupMidiInput(final MidiIn midiIn)
   {
      final NoteInput noteInput =
         midiIn.createNoteInput("LumaFader MIDI", "B?????");
      noteInput.setShouldConsumeEvents(false);
      midiIn.setMidiCallback((ShortMidiMessageReceivedCallback) this::onMidi);
   }

   private void attachRemoteObservers()
   {
      for (int i = 0; i < LumaFaderConstants.REMOTE_COUNT; i++)
      {
         attachRemoteParameterObservers(deviceRemoteParameters[i]);
         attachRemoteParameterObservers(trackRemoteParameters[i]);
      }
   }

   private void attachRemoteParameterObservers(final Parameter parameter)
   {
      parameter.exists().markInterested();
      parameter.name().markInterested();
      parameter.value().markInterested();

      parameter.value().addValueObserver(
         (DoubleValueChangedCallback) value -> scheduleVisibleStateUpdate());
      parameter.exists().addValueObserver(
         (BooleanValueChangedCallback) exists -> scheduleVisibleStateUpdate());
      parameter.name().addValueObserver(
         (StringValueChangedCallback) name -> scheduleVisibleStateUpdate());
   }

   private void attachSendObservers()
   {
      for (int i = 0; i < LumaFaderConstants.FADER_COUNT; i++)
      {
         final Send send = sends[i];
         send.exists().markInterested();
         send.name().markInterested();
         send.isEnabled().markInterested();
         send.value().markInterested();
         send.sendChannelColor().markInterested();

         send.exists().addValueObserver(
            (BooleanValueChangedCallback) exists -> scheduleVisibleStateUpdate());
         send.name().addValueObserver(
            (StringValueChangedCallback) name -> scheduleVisibleStateUpdate());
         send.isEnabled().addValueObserver(
            (BooleanValueChangedCallback) enabled -> scheduleVisibleStateUpdate());
         send.value().addValueObserver(
            (DoubleValueChangedCallback) value -> scheduleVisibleStateUpdate());
      }
   }

   private void attachDeviceObservers()
   {
      cursorDevice.exists().markInterested();
      cursorDevice.name().markInterested();
      cursorDevice.position().markInterested();
      cursorDevice.isRemoteControlsSectionVisible().markInterested();

      cursorDevice.exists().addValueObserver(
         (BooleanValueChangedCallback) exists -> scheduleVisibleStateUpdate());
      cursorDevice.name().addValueObserver(
         (StringValueChangedCallback) name -> focusNavigation.onCursorDeviceNameChanged());

      for (int i = 0; i < LumaFaderConstants.TRACK_DEVICE_BANK_MARK; i++)
      {
         markDeviceUiStateInterested(trackDeviceBank.getDevice(i));
         markDeviceUiStateInterested(siblingDeviceBank.getDevice(i));
      }
   }

   private void markDeviceUiStateInterested(final Device device)
   {
      device.exists().markInterested();
      device.name().markInterested();
      device.position().markInterested();
      device.isRemoteControlsSectionVisible().markInterested();
   }

   private void attachUtilityObservers()
   {
      lastTouchedParameter.exists().markInterested();
      lastTouchedParameter.name().markInterested();
      lastTouchedParameter.value().markInterested();

      lastTouchedParameter.exists().addValueObserver(
         (BooleanValueChangedCallback) exists -> scheduleVisibleStateUpdate());
      lastTouchedParameter.name().addValueObserver(
         (StringValueChangedCallback) name -> scheduleVisibleStateUpdate());
      lastTouchedParameter.value().addValueObserver(
         (DoubleValueChangedCallback) value -> scheduleVisibleStateUpdate());

      trackPan.value().markInterested();
      trackVolume.value().markInterested();
      trackPan.value().addValueObserver(
         (DoubleValueChangedCallback) value -> scheduleVisibleStateUpdate());
      trackVolume.value().addValueObserver(
         (DoubleValueChangedCallback) value -> scheduleVisibleStateUpdate());
   }

   private void attachTrackObservers()
   {
      cursorTrack.exists().markInterested();
      cursorTrack.name().markInterested();
      cursorTrack.color().markInterested();
      cursorTrack.position().markInterested();
      cursorTrack.exists().addValueObserver(
         (BooleanValueChangedCallback) exists -> scheduleVisibleStateUpdate());
   }

   private void onMidi(final ShortMidiMessage msg)
   {
      if (!msg.isControlChange())
      {
         return;
      }

      final int cc = msg.getData1();

      if (isUserMode())
      {
         if (handleModeActionCc(cc, msg.getData2()))
         {
            return;
         }
         return;
      }

      if (cc == LumaFaderConstants.FINE_MODIFIER_ACTION_CC)
      {
         fineModifierHeld = msg.getData2() >= 64;
         if (fineModifierHeld)
         {
            faderTakeover.disarmAll();
         }
         return;
      }

      if (handleModeActionCc(cc, msg.getData2()))
      {
         return;
      }

      if (cc == LumaFaderConstants.OVERLAY_1_ACTION_CC)
      {
         setOverlayHeld(msg.getData2() >= 64, 1);
         return;
      }

      if (cc == LumaFaderConstants.OVERLAY_2_ACTION_CC)
      {
         setOverlayHeld(msg.getData2() >= 64, 2);
         return;
      }

      if (cc == LumaFaderConstants.OVERLAY_3_ACTION_CC)
      {
         setOverlayHeld(msg.getData2() >= 64, 3);
         return;
      }

      if (handleNavActionCc(cc, msg.getData2()))
      {
         return;
      }

      if (isFourTrackMode())
      {
         fourTrackMidiHandler.handleFaderCc(cc, msg.getData2());
         return;
      }

      focusFaderMidi.handleFaderCc(cc, msg.getData2(), this::setLedDisplayKind);
   }

   private void setOverlayHeld(final boolean held, final int overlayNumber)
   {
      if (overlayNumber == 1)
      {
         overlay1Held = held;
      }
      else if (overlayNumber == 2)
      {
         overlay2Held = held;
      }
      else
      {
         overlay3Held = held;
      }

      if (isFourTrackMode())
      {
         if (overlayNumber == 1)
         {
            setLedDisplayKind(
               held
                  ? LumaFaderConstants.DISPLAY_FOUR_VOLUME
                  : LumaFaderConstants.DISPLAY_FOUR_SENDS);
         }
         else if (overlayNumber == 2)
         {
            setLedDisplayKind(
               held
                  ? LumaFaderConstants.DISPLAY_FOUR_PAN
                  : LumaFaderConstants.DISPLAY_FOUR_SENDS);
         }
         else
         {
            setLedDisplayKind(LumaFaderConstants.DISPLAY_FOUR_SENDS);
         }
      }
      else if (overlayNumber == 1)
      {
         setLedDisplayKind(
            held
               ? LumaFaderConstants.DISPLAY_REMOTES_5_8
               : LumaFaderConstants.DISPLAY_REMOTES);
      }
      else if (overlayNumber == 2)
      {
         setLedDisplayKind(
            held ? LumaFaderConstants.DISPLAY_SENDS : LumaFaderConstants.DISPLAY_REMOTES);
      }
      else
      {
         setLedDisplayKind(
            held ? LumaFaderConstants.DISPLAY_UTILITY : LumaFaderConstants.DISPLAY_REMOTES);
      }

      if (held)
      {
         sendVisibleState(SysexProtocol.EDGE_NONE);
      }
      else
      {
         scheduleVisibleStateUpdate();
      }
   }

   private boolean handleModeActionCc(final int cc, final int value)
   {
      if (value < 64)
      {
         return cc == LumaFaderConstants.MODE_FOCUS_ACTION_CC
            || cc == LumaFaderConstants.MODE_FOUR_TRACK_ACTION_CC
            || cc == LumaFaderConstants.MODE_USER_ACTION_CC;
      }

      switch (cc)
      {
         case LumaFaderConstants.MODE_FOCUS_ACTION_CC:
            switchMode(SysexProtocol.MODE_FOCUS);
            return true;
         case LumaFaderConstants.MODE_FOUR_TRACK_ACTION_CC:
            switchMode(SysexProtocol.MODE_FOUR_TRACK);
            return true;
         case LumaFaderConstants.MODE_USER_ACTION_CC:
            switchMode(SysexProtocol.MODE_USER);
            return true;
         default:
            return false;
      }
   }

   private void switchMode(final int newModeId)
   {
      if (modeId == newModeId)
      {
         scheduleVisibleStateUpdate();
         return;
      }

      final boolean leavingFourTrack = isFourTrackMode();
      modeId = newModeId;
      overlay1Held = false;
      overlay2Held = false;
      overlay3Held = false;

      if (leavingFourTrack && !isFourTrackMode())
      {
         setFourTrackWindowIndication(false);
      }

      if (isFourTrackMode())
      {
         ledDisplayKind = LumaFaderConstants.DISPLAY_FOUR_SENDS;
         fourTrackViewport.initPageAroundCursor(
            flatTrackBank, cursorTrack, LumaFaderConstants.MAIN_TRACK_BANK_SIZE);
         enableFourTrackWindowIndication();
         host.scheduleTask(
            this::enableFourTrackWindowIndication,
            LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
      }
      else if (isFocusMode())
      {
         ledDisplayKind = LumaFaderConstants.DISPLAY_REMOTES;
         focusNavigationState.editingTrackRemotes = false;
         host.scheduleTask(
            () -> focusNavigation.scheduleSelectFirstDeviceOnCursorTrack(),
            LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
      }
      else if (isUserMode())
      {
         ledDisplayKind = LumaFaderConstants.DISPLAY_REMOTES;
      }

      faderTakeover.disarmAll();
      faderNavPickup.arm();
      visibleStateSysex.sendModeChange(modeId);
      armFaderTakeoverForCurrentLayer();
      scheduleVisibleStateUpdate();
   }

   private boolean handleNavActionCc(final int cc, final int value)
   {
      if (isFourTrackMode())
      {
         return fourTrackMidiHandler.handleNavActionCc(cc, value);
      }
      return focusNavigation.handleNavActionCc(cc, value);
   }

   private void setLedDisplayKind(final int kind)
   {
      if (ledDisplayKind != kind)
      {
         ledDisplayKind = kind;
         armFaderTakeoverForCurrentLayer();
         scheduleVisibleStateUpdate();
      }
   }

   private void armFaderTakeoverForCurrentLayer()
   {
      final int[] hostMidi7 =
         visibleStatePublisher.hostMidi7ForDisplayedFaders(currentVisibleSnapshot());
      faderTakeover.arm(LumaFaderConstants.FADER_COUNT, hostMidi7);
   }

   private void scheduleVisibleStateUpdate()
   {
      visibleStatePublisher.scheduleUpdate();
   }

   private void sendVisibleState()
   {
      sendVisibleState(SysexProtocol.EDGE_NONE);
   }

   private void sendVisibleState(final int navRejectEdge)
   {
      visibleStatePublisher.send(
         new VisibleStatePublisher.Snapshot(
            modeId,
            ledDisplayKind,
            focusNavigationState.editingTrackRemotes,
            navRejectEdge));
   }

   private void onNavigationCompleted()
   {
      faderTakeover.disarmAll();
      faderNavPickup.arm();
      scheduleVisibleStateUpdate();
   }

   private void sendNavReject(final int edge)
   {
      sendVisibleState(edge);
   }

   @Override
   public void exit()
   {
      setFourTrackWindowIndication(false);
      ((ControllerHost) getHost()).println("LumaFader exited");
   }

   @Override
   public void flush()
   {
      visibleStatePublisher.flushIfDue();
   }
}
