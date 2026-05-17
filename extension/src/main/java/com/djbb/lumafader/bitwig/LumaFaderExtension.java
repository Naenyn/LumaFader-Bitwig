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
import com.bitwig.extension.controller.api.ColorValue;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.LastClickedParameter;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.SettableColorValue;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Focus mode: CC 20–23 → remotes 1–4; CC 24–27 → remotes 5–8 (track or device);
 * CC 28–31 → sends 1–4 (overlay 2); CC 32–35 → utility (overlay 3).
 * LED feedback via SysEx visible-state updates.
 */
public class LumaFaderExtension extends ControllerExtension
{
   private static final int MIDI_CHANNEL = 0;
   private static final int[] FADER_CCS_REMOTES_1_4 = {20, 21, 22, 23};
   private static final int[] FADER_CCS_REMOTES_5_8 = {24, 25, 26, 27};
   private static final int[] FADER_CCS_SENDS = {28, 29, 30, 31};
   private static final int[] FADER_CCS_UTILITY = {32, 33, 34, 35};
   /** Matches settings.json ACTION_CC.overlay_1 — held state for LED bank, not a fader. */
   private static final int OVERLAY_1_ACTION_CC = 50;
   /** Matches settings.json ACTION_CC.overlay_2 — sends overlay (button 3). */
   private static final int OVERLAY_2_ACTION_CC = 51;
   /** Matches settings.json ACTION_CC.overlay_3 — utility overlay (button 2). */
   private static final int OVERLAY_3_ACTION_CC = 52;
   /** Matches settings.json ACTION_CC.fine_modifier — 127 held / 0 release (pickup hint). */
   private static final int FINE_MODIFIER_ACTION_CC = 53;
   private static final int NAV_NEXT_TRACK_CC = 40;
   private static final int NAV_PREV_TRACK_CC = 41;
   private static final int NAV_NEXT_DEVICE_CC = 42;
   private static final int NAV_PREV_DEVICE_CC = 43;
   private static final int NAV_NEXT_TRACK_PAGE_CC = 44;
   private static final int NAV_PREV_TRACK_PAGE_CC = 45;
   private static final int NAV_NEXT_SEND_CC = 46;
   private static final int NAV_PREV_SEND_CC = 47;
   /** Outside FADER_CC_SENDS (28–31) and FADER_CC_UTILITY (32–35). */
   private static final int MODE_FOCUS_ACTION_CC = 60;
   private static final int MODE_FOUR_TRACK_ACTION_CC = 61;
   private static final int MODE_USER_ACTION_CC = 62;
   private static final int UTILITY_LAST_TOUCHED = 0;
   private static final int UTILITY_RESERVED = 1;
   private static final int UTILITY_PAN = 2;
   private static final int UTILITY_VOLUME = 3;
   private static final int REMOTE_COUNT = 8;
   private static final int FADER_COUNT = 4;
   private static final int TRACK_SEND_COUNT = 4;
   /** Sends per track in {@link #flatTrackBank} (send-page nav); Focus cursor uses {@link #TRACK_SEND_COUNT}. */
   private static final int FLAT_TRACK_BANK_SEND_COUNT = 16;
   private static final int LED_UPDATE_DEBOUNCE_MS = 25;
   /** Let cursor track/device settle after selectNext/Previous before reading exists(). */
   private static final int DEVICE_SELECT_DELAY_MS = 100;
   private static final int DEVICE_SELECT_MAX_ATTEMPTS = 3;
   private static final int MAIN_TRACK_BANK_SIZE = 256;
   /** Page-sized bank for Four-Track; drives Bitwig's track-window highlight. */
   private static final int FOUR_TRACK_BANK_SIZE = 4;
   /**
    * Non-zero scene count so {@link TrackBank#setShouldShowClipLauncherFeedback} can draw the
    * mixer track window. Slots are never bound or launched — indication only.
    */
   private static final int FOUR_TRACK_SCENE_COUNT = 8;
   private static final int TRACK_DEVICE_BANK_SIZE = 64;
   /** How many bank slots to markInterested for exists() scans. */
   private static final int TRACK_DEVICE_BANK_MARK = 16;
   private static final int DISPLAY_REMOTES = 0;
   private static final int DISPLAY_REMOTES_5_8 = 1;
   private static final int DISPLAY_SENDS = 2;
   private static final int DISPLAY_UTILITY = 3;
   /** Four-Track default layer and after overlay release. */
   private static final int DISPLAY_FOUR_SENDS = 4;
   /** Four-Track overlay 1: volume (reuses overlay_1 / CC 24–27 bank). */
   private static final int DISPLAY_FOUR_VOLUME = 5;
   /** Four-Track overlay 2: pan (reuses overlay_2 / CC 28–31 bank). */
   private static final int DISPLAY_FOUR_PAN = 6;

   private ControllerHost host;
   private int modeId = SysexProtocol.MODE_FOCUS;
   private final FourTrackViewport fourTrackViewport = new FourTrackViewport();
   private CursorTrack cursorTrack;
   /** Scans the flat track list for Four-Track visibility (not shown in the UI). */
   private TrackBank flatTrackBank;
   /** Four visible mixer tracks; scroll + indication draw the arranger track window. */
   private TrackBank fourTrackBank;
   private PinnableCursorDevice cursorDevice;
   private LastClickedParameter lastClicked;
   private Parameter lastTouchedParameter;
   private Parameter trackPan;
   private Parameter trackVolume;
   private DeviceBank trackDeviceBank;
   /** Devices in the chain of the currently selected cursor device. */
   private DeviceBank siblingDeviceBank;
   private CursorRemoteControlsPage deviceRemoteControlsPage;
   private CursorRemoteControlsPage trackRemoteControlsPage;
   private VisibleStateSysex visibleStateSysex;
   private final FaderTakeover faderTakeover = new FaderTakeover();
   private final FaderNavPickup faderNavPickup = new FaderNavPickup();
   private final Parameter[] deviceRemoteParameters = new Parameter[REMOTE_COUNT];
   private final Parameter[] trackRemoteParameters = new Parameter[REMOTE_COUNT];
   /** Track remotes are the left edge; device chain extends to the right. */
   private boolean editingTrackRemotes;
   /** Ignore cursor device name updates triggered by {@link #syncTrackRemotesUi()}. */
   private boolean syncingTrackRemotesUi;
   /** Suppress a duplicate left-edge reject right after entering track remotes. */
   private long trackRemotesEntryStartedMs;
   private final Send[] sends = new Send[FADER_COUNT];

   /** Which fader layer is painted on the four physical LEDs. */
   private int ledDisplayKind;
   private boolean ledUpdatePending;
   private long ledUpdateScheduledAt;
   private long lastFlushLedMs;
   private static final int FLUSH_LED_INTERVAL_MS = 100;
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
         TRACK_SEND_COUNT,
         0,
         true);
      flatTrackBank =
         host.createTrackBank(MAIN_TRACK_BANK_SIZE, FLAT_TRACK_BANK_SEND_COUNT, 0, true);
      fourTrackBank =
         host.createTrackBank(FOUR_TRACK_BANK_SIZE, 0, FOUR_TRACK_SCENE_COUNT, true);
      fourTrackBank.scrollPosition().markInterested();
      markFourTrackBankInterested();
      markFlatTrackBankInterested();
      attachFlatTrackBankObservers();
      cursorDevice = cursorTrack.createCursorDevice(
         "lumafader-device",
         "LumaFader Device",
         TRACK_SEND_COUNT,
         CursorDeviceFollowMode.FOLLOW_SELECTION);
      trackDeviceBank = cursorTrack.createDeviceBank(TRACK_DEVICE_BANK_SIZE);
      siblingDeviceBank = cursorDevice.createSiblingsDeviceBank(TRACK_DEVICE_BANK_MARK);
      deviceRemoteControlsPage = cursorDevice.createCursorRemoteControlsPage(REMOTE_COUNT);
      trackRemoteControlsPage =
         cursorTrack.createCursorRemoteControlsPage(
            "lumafader-track-remotes",
            REMOTE_COUNT,
            "");
      initRemoteParameters();
      initSendParameters();
      initUtilityParameters();

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

      host.scheduleTask(this::scheduleSelectFirstDeviceOnCursorTrack, DEVICE_SELECT_DELAY_MS);
      host.scheduleTask(this::sendVisibleState, 200);

      host.println("LumaFader: CC 20–35 via onMidi; takeover ramp on overlay/fine release");
      host.println("Navigation: ACTION_CC 40–47 chord pulses (mode-dependent)");
      host.println("Modes: ACTION_CC 60–62 double-tap");
      host.println("LED feedback: SysEx visible state (0x10)");
   }

   private boolean isFocusMode()
   {
      return modeId == SysexProtocol.MODE_FOCUS;
   }

   private boolean isFourTrackMode()
   {
      return modeId == SysexProtocol.MODE_FOUR_TRACK;
   }

   /**
    * Four-Track reads these on every bank slot; {@code markInterested()} is only legal in
    * {@link #init()}.
    */
   private void markFourTrackBankInterested()
   {
      for (int i = 0; i < FOUR_TRACK_BANK_SIZE; i++)
      {
         BitwigChannels.trackAt(fourTrackBank, i).exists().markInterested();
      }
   }

   private void markFlatTrackBankInterested()
   {
      for (int i = 0; i < MAIN_TRACK_BANK_SIZE; i++)
      {
         final Track track = BitwigChannels.trackAt(flatTrackBank, i);
         track.exists().markInterested();
         track.isActivated().markInterested();
         track.position().markInterested();
         track.color().markInterested();
         track.volume().markInterested();
         track.pan().markInterested();

         for (int s = 0; s < FLAT_TRACK_BANK_SEND_COUNT; s++)
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
      for (int i = 0; i < MAIN_TRACK_BANK_SIZE; i++)
      {
         final Track track = BitwigChannels.trackAt(flatTrackBank, i);
         track.isActivated().addValueObserver(
            (BooleanValueChangedCallback) activated -> onFlatTrackBankDataChanged());
         track.volume().value().addValueObserver(
            (DoubleValueChangedCallback) value -> onFlatTrackBankDataChanged());
         track.pan().value().addValueObserver(
            (DoubleValueChangedCallback) value -> onFlatTrackBankDataChanged());

         for (int s = 0; s < FLAT_TRACK_BANK_SEND_COUNT; s++)
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
      fourTrackViewport.rebuildVisibleTracks(flatTrackBank, MAIN_TRACK_BANK_SIZE);
      syncFourTrackBankToViewport();
      scheduleVisibleStateUpdate();
   }

   /**
    * Bitwig mixer highlight for the current Four-Track page. Does not follow UI track selection
    * after enter; does not launch or edit clips.
    */
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

   /** Scroll the 4-track bank to the current Four-Track page (mixer tracks only, no clips). */
   private void syncFourTrackBankToViewport()
   {
      if (fourTrackBank == null)
      {
         return;
      }
      final int position =
         fourTrackViewport.scrollPositionForPage(flatTrackBank);
      // API 6+: scrollPosition / scrollIntoView — not scrollToChannel / scrollToTrack.
      fourTrackBank.scrollPosition().set(position);
      fourTrackBank.scrollIntoView(position);
   }

   private void initRemoteParameters()
   {
      for (int i = 0; i < REMOTE_COUNT; i++)
      {
         final Parameter deviceParameter = deviceRemoteControlsPage.getParameter(i);
         deviceRemoteParameters[i] = deviceParameter;
         deviceParameter.markInterested();

         final Parameter trackParameter = trackRemoteControlsPage.getParameter(i);
         trackRemoteParameters[i] = trackParameter;
         trackParameter.markInterested();
      }
   }

   private Parameter remoteParameterAt(final int remoteIndex)
   {
      return editingTrackRemotes
         ? trackRemoteParameters[remoteIndex]
         : deviceRemoteParameters[remoteIndex];
   }

   private void initSendParameters()
   {
      for (int i = 0; i < FADER_COUNT; i++)
      {
         sends[i] = BitwigChannels.sendAt(cursorTrack, i);
         sends[i].markInterested();
      }
   }

   private void initUtilityParameters()
   {
      lastClicked =
         host.createLastClickedParameter("lumafader-last-touched", "LumaFader Last Touched");
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
      for (int i = 0; i < REMOTE_COUNT; i++)
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
      for (int i = 0; i < FADER_COUNT; i++)
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
         (StringValueChangedCallback) name -> onCursorDeviceNameChanged());

      for (int i = 0; i < TRACK_DEVICE_BANK_MARK; i++)
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

   private boolean isUserMode()
   {
      return modeId == SysexProtocol.MODE_USER;
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

      if (cc == FINE_MODIFIER_ACTION_CC)
      {
         fineModifierHeld = msg.getData2() >= 64;
         if (fineModifierHeld)
         {
            faderTakeover.disarmAll();
         }
         // Fine release: firmware keeps host value and uses relative pickup on the
         // fader — do not arm takeover (that ramps toward physical and undoes fine).
         return;
      }

      if (handleModeActionCc(cc, msg.getData2()))
      {
         return;
      }

      if (cc == OVERLAY_1_ACTION_CC)
      {
         setOverlayHeld(msg.getData2() >= 64, 1);
         return;
      }

      if (cc == OVERLAY_2_ACTION_CC)
      {
         setOverlayHeld(msg.getData2() >= 64, 2);
         return;
      }

      if (cc == OVERLAY_3_ACTION_CC)
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
         onMidiFourTrackFader(cc, msg.getData2());
         return;
      }

      for (int i = 0; i < FADER_COUNT; i++)
      {
         if (cc == FADER_CCS_UTILITY[i])
         {
            applyUtilityFaderValue(i, msg.getData2());
            setLedDisplayKind(DISPLAY_UTILITY);
            return;
         }
      }

      for (int i = 0; i < FADER_COUNT; i++)
      {
         if (cc == FADER_CCS_SENDS[i])
         {
            applySendFaderValue(i, msg.getData2());
            setLedDisplayKind(DISPLAY_SENDS);
            return;
         }
      }

      for (int i = 0; i < FADER_COUNT; i++)
      {
         if (cc == FADER_CCS_REMOTES_5_8[i])
         {
            applyRemoteFaderValue(4 + i, msg.getData2());
            setLedDisplayKind(DISPLAY_REMOTES_5_8);
            return;
         }
      }

      for (int i = 0; i < FADER_COUNT; i++)
      {
         if (cc == FADER_CCS_REMOTES_1_4[i])
         {
            applyRemoteFaderValue(i, msg.getData2());
            setLedDisplayKind(DISPLAY_REMOTES);
            return;
         }
      }
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
            setLedDisplayKind(held ? DISPLAY_FOUR_VOLUME : DISPLAY_FOUR_SENDS);
         }
         else if (overlayNumber == 2)
         {
            setLedDisplayKind(held ? DISPLAY_FOUR_PAN : DISPLAY_FOUR_SENDS);
         }
         else
         {
            setLedDisplayKind(DISPLAY_FOUR_SENDS);
         }
      }
      else if (overlayNumber == 1)
      {
         setLedDisplayKind(held ? DISPLAY_REMOTES_5_8 : DISPLAY_REMOTES);
      }
      else if (overlayNumber == 2)
      {
         setLedDisplayKind(held ? DISPLAY_SENDS : DISPLAY_REMOTES);
      }
      else
      {
         setLedDisplayKind(held ? DISPLAY_UTILITY : DISPLAY_REMOTES);
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
         return cc == MODE_FOCUS_ACTION_CC
            || cc == MODE_FOUR_TRACK_ACTION_CC
            || cc == MODE_USER_ACTION_CC;
      }

      switch (cc)
      {
         case MODE_FOCUS_ACTION_CC:
            switchMode(SysexProtocol.MODE_FOCUS);
            return true;
         case MODE_FOUR_TRACK_ACTION_CC:
            switchMode(SysexProtocol.MODE_FOUR_TRACK);
            return true;
         case MODE_USER_ACTION_CC:
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
         ledDisplayKind = DISPLAY_FOUR_SENDS;
         fourTrackViewport.initPageAroundCursor(
            flatTrackBank, cursorTrack, MAIN_TRACK_BANK_SIZE);
         enableFourTrackWindowIndication();
         host.scheduleTask(this::enableFourTrackWindowIndication, DEVICE_SELECT_DELAY_MS);
      }
      else if (isFocusMode())
      {
         ledDisplayKind = DISPLAY_REMOTES;
         editingTrackRemotes = false;
         host.scheduleTask(this::scheduleSelectFirstDeviceOnCursorTrack, DEVICE_SELECT_DELAY_MS);
      }
      else if (isUserMode())
      {
         ledDisplayKind = DISPLAY_REMOTES;
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
         return handleFourTrackNavActionCc(cc, value);
      }

      if (value < 64)
      {
         return cc == NAV_NEXT_TRACK_CC
            || cc == NAV_PREV_TRACK_CC
            || cc == NAV_NEXT_DEVICE_CC
            || cc == NAV_PREV_DEVICE_CC;
      }

      switch (cc)
      {
         case NAV_NEXT_TRACK_CC:
            navigateTrack(1);
            return true;
         case NAV_PREV_TRACK_CC:
            navigateTrack(-1);
            return true;
         case NAV_NEXT_DEVICE_CC:
            navigateDevice(1);
            return true;
         case NAV_PREV_DEVICE_CC:
            navigateDevice(-1);
            return true;
         default:
            return false;
      }
   }

   private boolean handleFourTrackNavActionCc(final int cc, final int value)
   {
      if (value < 64)
      {
         return cc == NAV_NEXT_TRACK_PAGE_CC
            || cc == NAV_PREV_TRACK_PAGE_CC
            || cc == NAV_NEXT_SEND_CC
            || cc == NAV_PREV_SEND_CC;
      }

      switch (cc)
      {
         case NAV_NEXT_TRACK_PAGE_CC:
            if (fourTrackViewport.navigatePage(1))
            {
               syncFourTrackBankToViewport();
               onNavigationCompleted();
            }
            else
            {
               sendNavReject(SysexProtocol.EDGE_BOTTOM);
            }
            return true;

         case NAV_PREV_TRACK_PAGE_CC:
            if (fourTrackViewport.navigatePage(-1))
            {
               syncFourTrackBankToViewport();
               onNavigationCompleted();
            }
            else
            {
               sendNavReject(SysexProtocol.EDGE_TOP);
            }
            return true;

         case NAV_NEXT_SEND_CC:
            if (fourTrackViewport.navigateSend(
               flatTrackBank, MAIN_TRACK_BANK_SIZE, FLAT_TRACK_BANK_SEND_COUNT, 1))
            {
               onNavigationCompleted();
            }
            else
            {
               sendNavReject(SysexProtocol.EDGE_RIGHT);
            }
            return true;

         case NAV_PREV_SEND_CC:
            if (fourTrackViewport.navigateSend(
               flatTrackBank, MAIN_TRACK_BANK_SIZE, FLAT_TRACK_BANK_SEND_COUNT, -1))
            {
               onNavigationCompleted();
            }
            else
            {
               sendNavReject(SysexProtocol.EDGE_LEFT);
            }
            return true;

         default:
            return false;
      }
   }

   private void onMidiFourTrackFader(final int cc, final int incomingMidi7)
   {
      for (int i = 0; i < FADER_COUNT; i++)
      {
         if (cc == FADER_CCS_REMOTES_5_8[i])
         {
            applyFourTrackVolumeFaderValue(i, incomingMidi7);
            setLedDisplayKind(DISPLAY_FOUR_VOLUME);
            return;
         }
      }

      for (int i = 0; i < FADER_COUNT; i++)
      {
         if (cc == FADER_CCS_SENDS[i])
         {
            applyFourTrackPanFaderValue(i, incomingMidi7);
            setLedDisplayKind(DISPLAY_FOUR_PAN);
            return;
         }
      }

      for (int i = 0; i < FADER_COUNT; i++)
      {
         if (cc == FADER_CCS_REMOTES_1_4[i])
         {
            applyFourTrackSendFaderValue(i, incomingMidi7);
            setLedDisplayKind(DISPLAY_FOUR_SENDS);
            return;
         }
      }
   }

   private void applyFourTrackSendFaderValue(final int slot, final int incomingMidi7)
   {
      final Send send = fourTrackViewport.sendAtSlot(flatTrackBank, slot);
      applyFourTrackSendParameter(slot, send, incomingMidi7);
   }

   private void applyFourTrackSendParameter(
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
               scheduleVisibleStateUpdate();
            },
            0);
         return;
      }

      send.set(midi7, 128);
      scheduleVisibleStateUpdate();
   }

   private void applyFourTrackVolumeFaderValue(final int slot, final int incomingMidi7)
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
      scheduleVisibleStateUpdate();
   }

   private void applyFourTrackPanFaderValue(final int slot, final int incomingMidi7)
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

      applyPanParameter(track.pan(), midi7);
      scheduleVisibleStateUpdate();
   }

   private void applyPanParameter(final Parameter pan, final int midi7)
   {
      final int current = VisibleStateSysex.parameterToMidi7(pan.value().get());
      final boolean incomingNearCenter =
         Math.abs(midi7 - SysexProtocol.PAN_CENTER_CC) <= SysexProtocol.PAN_DEADZONE_CC;
      final boolean hostNearCenter =
         Math.abs(current - SysexProtocol.PAN_CENTER_CC) <= SysexProtocol.PAN_DEADZONE_CC;

      if (incomingNearCenter && hostNearCenter)
      {
         if (current != SysexProtocol.PAN_CENTER_CC)
         {
            pan.set(SysexProtocol.PAN_CENTER_CC, 128);
         }
         return;
      }

      pan.set(midi7, 128);
   }

   private void navigateTrack(final int direction)
   {
      if (!cursorTrack.exists().get())
      {
         sendNavReject(
            direction > 0 ? SysexProtocol.EDGE_BOTTOM : SysexProtocol.EDGE_TOP);
         return;
      }

      final boolean canMove =
         direction > 0
            ? cursorTrack.hasNext().get()
            : cursorTrack.hasPrevious().get();
      if (!canMove)
      {
         sendNavReject(
            direction > 0 ? SysexProtocol.EDGE_BOTTOM : SysexProtocol.EDGE_TOP);
         return;
      }

      if (direction > 0)
      {
         cursorTrack.selectNext();
      }
      else
      {
         cursorTrack.selectPrevious();
      }

      // Staged: track in arranger/mixer, then first device in the device list.
      host.scheduleTask(
         () -> {
            focusCursorTrackInUi();
            if (editingTrackRemotes)
            {
               syncTrackRemotesUi();
               onNavigationCompleted();
               return;
            }
            host.scheduleTask(
               () -> {
                  cursorTrack.selectFirstChild();
                  scheduleSelectFirstDeviceAfterTrackNav();
               },
               DEVICE_SELECT_DELAY_MS);
         },
         DEVICE_SELECT_DELAY_MS);
   }

   private void scheduleSelectFirstDeviceAfterTrackNav()
   {
      scheduleSelectFirstDeviceAttempt(0, true);
   }

   private void scheduleSelectFirstDeviceOnCursorTrack()
   {
      scheduleSelectFirstDeviceAttempt(0, false);
   }

   /**
    * After track navigation, Bitwig needs a moment before the device chain is valid.
    * Retries until {@link #cursorDevice} exists or attempts are exhausted.
    */
   private void scheduleSelectFirstDeviceAttempt(
      final int attempt,
      final boolean finishNav)
   {
      host.scheduleTask(
         () -> {
            final boolean ready = ensureDeviceSelectedOnCursorTrack(attempt);
            if (ready || attempt + 1 >= DEVICE_SELECT_MAX_ATTEMPTS)
            {
               if (finishNav)
               {
                  onNavigationCompleted();
               }
               return;
            }
            scheduleSelectFirstDeviceAttempt(attempt + 1, finishNav);
         },
         DEVICE_SELECT_DELAY_MS);
   }

   /**
    * Select the first device on {@link #cursorTrack} and bind {@link #cursorDevice}.
    *
    * @return true when the cursor device and at least one remote parameter are ready
    */
   private boolean ensureDeviceSelectedOnCursorTrack(final int attempt)
   {
      if (!cursorTrack.exists().get())
         return false;

      if (attempt == 0)
      {
         focusCursorTrackInUi();
         trackDeviceBank.scrollTo(0);
         cursorTrack.selectFirstChild();
      }

      editingTrackRemotes = false;

      final Device target = resolveFirstDeviceOnCursorTrack();
      if (target != null)
      {
         target.selectInEditor();
         cursorDevice.selectDevice(target);
      }
      else if (attempt == 0)
      {
         cursorDevice.selectFirstInChannel(cursorTrack);
      }

      cursorDevice.selectInEditor();

      // Apply remotes expand/collapse once (not on every retry — that caused UI looping).
      if (attempt == 0 || attempt + 1 >= DEVICE_SELECT_MAX_ATTEMPTS)
      {
         applyDeviceRemotesUi(target);
      }

      if (isRemoteTargetReady())
         return true;

      if (isDeviceCursorReady())
         return attempt + 1 >= DEVICE_SELECT_MAX_ATTEMPTS;

      return false;
   }

   /** Cursor device is bound (track nav / device nav can use {@link #cursorDevice}). */
   private boolean isDeviceCursorReady()
   {
      return cursorDevice.exists().get();
   }

   /** At least one mapped remote on the active track/device target. */
   private boolean isRemoteTargetReady()
   {
      if (editingTrackRemotes)
      {
         return cursorTrack.exists().get() && hasActiveRemoteSlot(trackRemoteParameters);
      }
      return cursorDevice.exists().get() && hasActiveRemoteSlot(deviceRemoteParameters);
   }

   private static boolean hasActiveRemoteSlot(final Parameter[] parameters)
   {
      for (int i = 0; i < REMOTE_COUNT; i++)
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

   /** Track device bank first, then sibling chain on the cursor device. */
   private Device resolveFirstDeviceOnCursorTrack()
   {
      final Device fromTrackBank = findFirstDeviceInBank(trackDeviceBank);
      if (fromTrackBank != null)
      {
         return fromTrackBank;
      }

      return findFirstDeviceInBank(siblingDeviceBank);
   }

   private Device findFirstDeviceInBank(final DeviceBank bank)
   {
      for (int i = 0; i < TRACK_DEVICE_BANK_MARK; i++)
      {
         final Device device = bank.getDevice(i);
         if (device.exists().get())
         {
            return device;
         }
      }
      return null;
   }

   private void navigateDevice(final int direction)
   {
      if (!cursorTrack.exists().get())
      {
         sendNavReject(
            direction > 0 ? SysexProtocol.EDGE_RIGHT : SysexProtocol.EDGE_LEFT);
         return;
      }

      navigateDeviceAfterEnsure(direction);
   }

   private void navigateDeviceAfterEnsure(final int direction)
   {
      if (!cursorTrack.exists().get())
      {
         sendNavReject(
            direction > 0 ? SysexProtocol.EDGE_RIGHT : SysexProtocol.EDGE_LEFT);
         return;
      }

      if (editingTrackRemotes)
      {
         if (direction < 0)
         {
            if (isWithinTrackRemotesEntryGracePeriod())
            {
               return;
            }
            sendNavReject(SysexProtocol.EDGE_LEFT);
            return;
         }
         leaveTrackRemotesForFirstDevice();
         return;
      }

      if (!isDeviceCursorReady())
      {
         if (direction < 0)
         {
            scheduleTrackRemotesUiAndComplete();
            return;
         }
         cursorDevice.selectFirstInChannel(cursorTrack);
         if (!isDeviceCursorReady())
         {
            sendNavReject(SysexProtocol.EDGE_RIGHT);
            return;
         }
      }

      if (direction < 0)
      {
         // On this API the first device also reports hasPrevious() == false (same as
         // the track-header slot). Step into track remotes whenever we are at the left
         // end of the device cursor and not already editing track remotes.
         if (!cursorDevice.hasPrevious().get())
         {
            scheduleTrackRemotesUiAndComplete();
            return;
         }
         cursorDevice.selectPrevious();
      }
      else
      {
         if (!cursorDevice.hasNext().get())
         {
            sendNavReject(SysexProtocol.EDGE_RIGHT);
            return;
         }
         cursorDevice.selectNext();
      }

      host.scheduleTask(
         () -> {
            editingTrackRemotes = false;
            syncDeviceNavUi();
            onNavigationCompleted();
         },
         DEVICE_SELECT_DELAY_MS);
   }

   private boolean isWithinTrackRemotesEntryGracePeriod()
   {
      return System.currentTimeMillis() - trackRemotesEntryStartedMs < 350;
   }

   /**
    * True when the cursor device is the first device in the chain (for observers only;
    * navigation uses {@code !hasPrevious()} because position indices do not match).
    */
   private boolean isOnFirstDeviceInChain()
   {
      if (!isDeviceCursorReady())
      {
         return false;
      }

      final Device first = resolveFirstDeviceOnCursorTrack();
      if (first == null || !first.exists().get())
      {
         return false;
      }

      if (cursorDevice.name().get().equals(first.name().get()))
      {
         return true;
      }

      return cursorDevice.position().get() == first.position().get();
   }

   private boolean isOnTrackChainDevice()
   {
      if (!isDeviceCursorReady())
      {
         return false;
      }

      final int cursorPosition = cursorDevice.position().get();
      for (int i = 0; i < TRACK_DEVICE_BANK_MARK; i++)
      {
         final Device device = trackDeviceBank.getDevice(i);
         if (device.exists().get() && device.position().get() == cursorPosition)
         {
            return true;
         }
      }
      return false;
   }

   private void leaveTrackRemotesForFirstDevice()
   {
      editingTrackRemotes = false;
      syncingTrackRemotesUi = true;
      cursorDevice.selectFirstInChannel(cursorTrack);
      host.scheduleTask(
         () -> {
            syncingTrackRemotesUi = false;
            if (isDeviceCursorReady())
            {
               syncDeviceNavUi();
               onNavigationCompleted();
            }
            else
            {
               editingTrackRemotes = true;
               sendNavReject(SysexProtocol.EDGE_RIGHT);
            }
         },
         DEVICE_SELECT_DELAY_MS);
   }

   private void scheduleTrackRemotesUiAndComplete()
   {
      trackRemotesEntryStartedMs = System.currentTimeMillis();
      host.scheduleTask(
         () -> {
            syncTrackRemotesUi();
            onNavigationCompleted();
         },
         DEVICE_SELECT_DELAY_MS);
   }

   /**
    * Bind faders to track remotes and focus the track in the UI. The device-panel
    * track remotes strip cannot be expanded via the public API (see README).
    */
   private void syncTrackRemotesUi()
   {
      editingTrackRemotes = true;
      syncingTrackRemotesUi = true;
      focusCursorTrackInUi();
      cursorTrack.selectChannel(cursorTrack);
      cursorTrack.selectInEditor();

      host.scheduleTask(
         () -> {
            syncingTrackRemotesUi = false;
            scheduleVisibleStateUpdate();
         },
         DEVICE_SELECT_DELAY_MS);
   }

   private void onCursorDeviceNameChanged()
   {
      if (syncingTrackRemotesUi)
      {
         scheduleVisibleStateUpdate();
         return;
      }
      if (editingTrackRemotes
         && isOnTrackChainDevice()
         && !isOnFirstDeviceInChain())
      {
         editingTrackRemotes = false;
      }
      scheduleVisibleStateUpdate();
   }

   /** Editor + remotes UI for the current {@link #cursorDevice} (no selectDevice). */
   private void syncDeviceNavUi()
   {
      editingTrackRemotes = false;
      final Device bankDevice = findBankDeviceForCursor();
      if (bankDevice != null && bankDevice.exists().get())
      {
         bankDevice.selectInEditor();
      }
      cursorDevice.selectInEditor();
      applyDeviceRemotesUi(bankDevice);
   }

   /** Track row selected in arranger and mixer (like clicking the track header). */
   private void focusCursorTrackInUi()
   {
      cursorTrack.selectInEditor();
      cursorTrack.selectInMixer();
      cursorTrack.makeVisibleInArranger();
      cursorTrack.makeVisibleInMixer();
   }

   /** Collapse only the remote-controls strip on other devices in the chain. */
   private void collapseOtherDeviceRemotesOnTrack(final Device keepOpen)
   {
      final String keepName =
         keepOpen != null && keepOpen.exists().get() ? keepOpen.name().get() : "";

      for (int i = 0; i < TRACK_DEVICE_BANK_MARK; i++)
      {
         final Device device = trackDeviceBank.getDevice(i);
         if (!device.exists().get())
         {
            continue;
         }
         if (!keepName.isEmpty() && device.name().get().equals(keepName))
         {
            continue;
         }
         device.isRemoteControlsSectionVisible().set(false);
      }
   }

   /** Open remotes on {@code active}; close remotes on sibling devices only. */
   private void applyDeviceRemotesUi(final Device active)
   {
      collapseOtherDeviceRemotesOnTrack(active);

      if (active != null && active.exists().get())
      {
         active.isRemoteControlsSectionVisible().set(true);
      }
      cursorDevice.isRemoteControlsSectionVisible().set(true);
   }

   /**
    * Select a device in the device panel and bind {@link #cursorDevice}.
    */
   private void focusDeviceInUi(final Device bankDevice)
   {
      editingTrackRemotes = false;
      if (bankDevice != null && bankDevice.exists().get())
      {
         bankDevice.selectInEditor();
         cursorDevice.selectDevice(bankDevice);
      }
      else
      {
         cursorDevice.selectFirstInChannel(cursorTrack);
      }

      cursorDevice.selectInEditor();
      applyDeviceRemotesUi(bankDevice);
   }

   /** Match {@link #cursorDevice} to a device in {@link #trackDeviceBank} by name. */
   private Device findBankDeviceForCursor()
   {
      if (!cursorDevice.exists().get())
      {
         return null;
      }

      final String cursorName = cursorDevice.name().get();
      for (int i = 0; i < TRACK_DEVICE_BANK_MARK; i++)
      {
         final Device device = trackDeviceBank.getDevice(i);
         if (device.exists().get() && device.name().get().equals(cursorName))
         {
            return device;
         }
      }
      return findFirstDeviceInBank(trackDeviceBank);
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

      final int faderIndex = remoteIndex % FADER_COUNT;
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
      scheduleVisibleStateUpdate();
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
         host.scheduleTask(() -> {
            send.set(midi7, 128);
            scheduleVisibleStateUpdate();
         }, 0);
         return;
      }

      send.set(midi7, 128);
      scheduleVisibleStateUpdate();
   }

   private void applyUtilityFaderValue(final int index, final int incomingMidi7)
   {
      if (index == UTILITY_RESERVED)
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
         case UTILITY_LAST_TOUCHED:
            if (!isLastTouchedActive())
            {
               return;
            }
            lastTouchedParameter.set(midi7, 128);
            break;

         case UTILITY_PAN:
            applyPanFaderValue(midi7);
            break;

         case UTILITY_VOLUME:
            trackVolume.set(midi7, 128);
            break;

         default:
            return;
      }

      scheduleVisibleStateUpdate();
   }

   /**
    * Pan deadzone on the host: when Bitwig is already near center, ignore small MIDI
    * until the fader leaves the deadzone (easy re-center). LEDs always reflect host value.
    */
   private void applyPanFaderValue(final int midi7)
   {
      final int current =
         VisibleStateSysex.parameterToMidi7(trackPan.value().get());
      final boolean incomingNearCenter =
         Math.abs(midi7 - SysexProtocol.PAN_CENTER_CC) <= SysexProtocol.PAN_DEADZONE_CC;
      final boolean hostNearCenter =
         Math.abs(current - SysexProtocol.PAN_CENTER_CC) <= SysexProtocol.PAN_DEADZONE_CC;

      if (incomingNearCenter && hostNearCenter)
      {
         if (current != SysexProtocol.PAN_CENTER_CC)
         {
            trackPan.set(SysexProtocol.PAN_CENTER_CC, 128);
         }
         return;
      }

      trackPan.set(midi7, 128);
   }

   private boolean isLastTouchedActive()
   {
      return VisibleStateSysex.isRemoteSlotActive(
         true,
         lastTouchedParameter.exists().get(),
         lastTouchedParameter.name().get());
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
      final int[] hostMidi7 = new int[FADER_COUNT];
      for (int i = 0; i < FADER_COUNT; i++)
      {
         hostMidi7[i] = hostMidi7ForDisplayedFader(i);
      }
      faderTakeover.arm(FADER_COUNT, hostMidi7);
   }

   private int hostMidi7ForDisplayedFader(final int faderIndex)
   {
      if (isFourTrackMode())
      {
         return hostMidi7ForFourTrackFader(faderIndex);
      }

      if (ledDisplayKind == DISPLAY_SENDS)
      {
         final Send send = sends[faderIndex];
         if (!cursorTrack.exists().get()
            || !VisibleStateSysex.isSendSlotDefinedInProject(send))
         {
            return 0;
         }
         return VisibleStateSysex.parameterToMidi7(send.value().get());
      }

      if (ledDisplayKind == DISPLAY_UTILITY)
      {
         if (!cursorTrack.exists().get())
         {
            return 0;
         }
         switch (faderIndex)
         {
            case UTILITY_LAST_TOUCHED:
               if (!isLastTouchedActive())
               {
                  return 0;
               }
               return VisibleStateSysex.parameterToMidi7(lastTouchedParameter.value().get());

            case UTILITY_PAN:
               return VisibleStateSysex.parameterToMidi7(trackPan.value().get());

            case UTILITY_VOLUME:
               return VisibleStateSysex.parameterToMidi7(trackVolume.value().get());

            default:
               return 0;
         }
      }

      if (!isRemoteTargetReady())
      {
         return 0;
      }

      final int remoteOffset = ledDisplayKind == DISPLAY_REMOTES_5_8 ? 4 : 0;
      final Parameter parameter = remoteParameterAt(remoteOffset + faderIndex);
      if (!VisibleStateSysex.isRemoteSlotActive(
         true,
         parameter.exists().get(),
         parameter.name().get()))
      {
         return 0;
      }
      return VisibleStateSysex.parameterToMidi7(parameter.value().get());
   }

   private int hostMidi7ForFourTrackFader(final int slot)
   {
      if (!fourTrackViewport.isSlotActive(slot))
      {
         return 0;
      }

      if (ledDisplayKind == DISPLAY_FOUR_VOLUME)
      {
         final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, slot);
         return VisibleStateSysex.parameterToMidi7(track.volume().value().get());
      }

      if (ledDisplayKind == DISPLAY_FOUR_PAN)
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

   private void scheduleVisibleStateUpdate()
   {
      if (ledUpdatePending)
      {
         return;
      }
      ledUpdatePending = true;
      host.scheduleTask(this::runDebouncedVisibleStateUpdate, LED_UPDATE_DEBOUNCE_MS);
   }

   private void runDebouncedVisibleStateUpdate()
   {
      final long now = System.currentTimeMillis();
      if (now - ledUpdateScheduledAt < LED_UPDATE_DEBOUNCE_MS)
      {
         host.scheduleTask(this::runDebouncedVisibleStateUpdate, LED_UPDATE_DEBOUNCE_MS);
         return;
      }
      ledUpdatePending = false;
      ledUpdateScheduledAt = now;
      sendVisibleState();
   }

   private void sendVisibleState()
   {
      sendVisibleState(SysexProtocol.EDGE_NONE);
   }

   private void sendVisibleState(final int navRejectEdge)
   {
      if (visibleStateSysex == null)
      {
         return;
      }

      final int[] faderModes = new int[FADER_COUNT];
      final int[] faderValues = new int[FADER_COUNT];
      final int[][] faderColors = new int[FADER_COUNT][3];
      final int[][] buttonColors = new int[FADER_COUNT][3];

      if (isFourTrackMode())
      {
         fourTrackViewport.rebuildVisibleTracks(flatTrackBank, MAIN_TRACK_BANK_SIZE);
         syncFourTrackBankToViewport();
         if (ledDisplayKind == DISPLAY_FOUR_VOLUME)
         {
            fillFourTrackVolumeFaderState(faderModes, faderValues, faderColors, buttonColors);
         }
         else if (ledDisplayKind == DISPLAY_FOUR_PAN)
         {
            fillFourTrackPanFaderState(faderModes, faderValues, faderColors, buttonColors);
         }
         else
         {
            fillFourTrackSendFaderState(faderModes, faderValues, faderColors, buttonColors);
         }
      }
      else if (modeId == SysexProtocol.MODE_USER)
      {
         for (int i = 0; i < FADER_COUNT; i++)
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
         }
      }
      else if (ledDisplayKind == DISPLAY_SENDS)
      {
         fillSendFaderState(faderModes, faderValues, faderColors, buttonColors);
      }
      else if (ledDisplayKind == DISPLAY_UTILITY)
      {
         fillUtilityFaderState(faderModes, faderValues, faderColors, buttonColors);
      }
      else
      {
         fillRemoteFaderState(faderModes, faderValues, faderColors, buttonColors);
      }

      visibleStateSysex.send(
         modeId,
         sysexOverlayId(),
         editingTrackRemotes
            ? SysexProtocol.REMOTE_SCOPE_TRACK
            : SysexProtocol.REMOTE_SCOPE_DEVICE,
         faderModes,
         faderValues,
         faderColors,
         buttonColors,
         navRejectEdge);
   }

   private int sysexOverlayId()
   {
      if (isFourTrackMode())
      {
         if (ledDisplayKind == DISPLAY_FOUR_VOLUME)
         {
            return 1;
         }
         if (ledDisplayKind == DISPLAY_FOUR_PAN)
         {
            return 2;
         }
         return 0;
      }

      if (ledDisplayKind == DISPLAY_REMOTES_5_8)
      {
         return 1;
      }
      if (ledDisplayKind == DISPLAY_SENDS)
      {
         return 2;
      }
      if (ledDisplayKind == DISPLAY_UTILITY)
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
      for (int i = 0; i < FADER_COUNT; i++)
      {
         buttonColors[i][0] = 0;
         buttonColors[i][1] = 0;
         buttonColors[i][2] = 0;

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
         faderColors[i][0] = rgb[0];
         faderColors[i][1] = rgb[1];
         faderColors[i][2] = rgb[2];

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
      for (int i = 0; i < FADER_COUNT; i++)
      {
         buttonColors[i][0] = 0;
         buttonColors[i][1] = 0;
         buttonColors[i][2] = 0;

         if (!fourTrackViewport.isSlotActive(i))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, i);
         final int[] rgb = midiRgbFromColorValue(track.color());
         faderColors[i][0] = rgb[0];
         faderColors[i][1] = rgb[1];
         faderColors[i][2] = rgb[2];
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
      for (int i = 0; i < FADER_COUNT; i++)
      {
         buttonColors[i][0] = 0;
         buttonColors[i][1] = 0;
         buttonColors[i][2] = 0;

         if (!fourTrackViewport.isSlotActive(i))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final Track track = fourTrackViewport.trackAtSlot(flatTrackBank, i);
         final int[] rgb = midiRgbFromColorValue(track.color());
         faderColors[i][0] = rgb[0];
         faderColors[i][1] = rgb[1];
         faderColors[i][2] = rgb[2];
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

      for (int i = 0; i < FADER_COUNT; i++)
      {
         buttonColors[i][0] = 0;
         buttonColors[i][1] = 0;
         buttonColors[i][2] = 0;

         if (i == UTILITY_RESERVED || !trackPresent)
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         if (i == UTILITY_LAST_TOUCHED)
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
            i == UTILITY_PAN || i == UTILITY_VOLUME
               ? midiRgbFromColorValue(cursorTrack.color())
               : BitwigRemoteColors.midiRgbForUtilitySlot(i);
         faderColors[i][0] = rgb[0];
         faderColors[i][1] = rgb[1];
         faderColors[i][2] = rgb[2];

         if (i == UTILITY_PAN)
         {
            faderModes[i] = SysexProtocol.FADER_MODE_PAN;
            faderValues[i] = VisibleStateSysex.parameterToMidi7(trackPan.value().get());
         }
         else if (i == UTILITY_VOLUME)
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

      for (int i = 0; i < FADER_COUNT; i++)
      {
         buttonColors[i][0] = 0;
         buttonColors[i][1] = 0;
         buttonColors[i][2] = 0;

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
         faderColors[i][0] = rgb[0];
         faderColors[i][1] = rgb[1];
         faderColors[i][2] = rgb[2];

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
      final int[] faderModes,
      final int[] faderValues,
      final int[][] faderColors,
      final int[][] buttonColors)
   {
      final boolean targetPresent =
         editingTrackRemotes
            ? cursorTrack.exists().get()
            : cursorDevice.exists().get();
      final boolean remotesActive = isRemoteTargetReady();
      final int remoteOffset = ledDisplayKind == DISPLAY_REMOTES_5_8 ? 4 : 0;

      for (int i = 0; i < FADER_COUNT; i++)
      {
         buttonColors[i][0] = 0;
         buttonColors[i][1] = 0;
         buttonColors[i][2] = 0;

         if (!targetPresent)
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final Parameter parameter = remoteParameterAt(remoteOffset + i);
         final boolean exists = parameter.exists().get();
         final String name = parameter.name().get();
         if (!remotesActive
            || !VisibleStateSysex.isRemoteSlotActive(true, exists, name))
         {
            VisibleStateSysex.setInactiveFader(faderModes, faderValues, faderColors, i);
            continue;
         }

         final int[] rgb = BitwigRemoteColors.midiRgbForRemoteSlot(remoteOffset + i);
         faderColors[i][0] = rgb[0];
         faderColors[i][1] = rgb[1];
         faderColors[i][2] = rgb[2];

         faderModes[i] = VisibleStateSysex.faderModeForParameter(targetPresent, exists, name);
         faderValues[i] = VisibleStateSysex.parameterToMidi7(parameter.value().get());
      }
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

   @Override
   public void exit()
   {
      setFourTrackWindowIndication(false);
      ((ControllerHost) getHost()).println("LumaFader exited");
   }

   @Override
   public void flush()
   {
      final long now = System.currentTimeMillis();
      if (now - lastFlushLedMs >= FLUSH_LED_INTERVAL_MS)
      {
         lastFlushLedMs = now;
         sendVisibleState();
      }
   }
}
