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
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Focus workspace: CC 20–23 → remotes 1–4; CC 24–27 → remotes 5–8 (track or device);
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
   private static final int UTILITY_LAST_TOUCHED = 0;
   private static final int UTILITY_RESERVED = 1;
   private static final int UTILITY_PAN = 2;
   private static final int UTILITY_VOLUME = 3;
   private static final int REMOTE_COUNT = 8;
   private static final int FADER_COUNT = 4;
   private static final int TRACK_SEND_COUNT = 4;
   private static final int LED_UPDATE_DEBOUNCE_MS = 25;
   /** Let cursor track/device settle after selectNext/Previous before reading exists(). */
   private static final int DEVICE_SELECT_DELAY_MS = 100;
   private static final int DEVICE_SELECT_MAX_ATTEMPTS = 3;
   private static final int MAIN_TRACK_BANK_SIZE = 256;
   private static final int TRACK_DEVICE_BANK_SIZE = 64;
   /** How many bank slots to markInterested for exists() scans. */
   private static final int TRACK_DEVICE_BANK_MARK = 16;

   private static final int DISPLAY_REMOTES = 0;
   private static final int DISPLAY_REMOTES_5_8 = 1;
   private static final int DISPLAY_SENDS = 2;
   private static final int DISPLAY_UTILITY = 3;

   private ControllerHost host;
   private CursorTrack cursorTrack;
   /** Main + FX + master; follows {@link #cursorTrack} in the arranger. */
   private TrackBank flatTrackBank;
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
   private static final int UTILITY_RAINBOW_ANIMATION_MS = 50;
   private boolean utilityRainbowAnimationRunning;

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
         host.createTrackBank(MAIN_TRACK_BANK_SIZE, TRACK_SEND_COUNT, 0, true);
      flatTrackBank.followCursorTrack(cursorTrack);
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
      host.println("Navigation: ACTION_CC 40–43 chord pulses → track/device cursor");
      host.println("LED feedback: SysEx visible state (0x10)");
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
         sends[i] = cursorTrack.getSend(i);
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
      if (cc == FINE_MODIFIER_ACTION_CC)
      {
         if (msg.getData2() >= 64)
         {
            faderTakeover.disarmAll();
         }
         // Fine release: firmware keeps host value and uses relative pickup on the
         // fader — do not arm takeover (that ramps toward physical and undoes fine).
         return;
      }

      if (cc == OVERLAY_1_ACTION_CC)
      {
         setLedDisplayKind(msg.getData2() >= 64 ? DISPLAY_REMOTES_5_8 : DISPLAY_REMOTES);
         return;
      }

      if (cc == OVERLAY_2_ACTION_CC)
      {
         setLedDisplayKind(msg.getData2() >= 64 ? DISPLAY_SENDS : DISPLAY_REMOTES);
         return;
      }

      if (cc == OVERLAY_3_ACTION_CC)
      {
         setLedDisplayKind(msg.getData2() >= 64 ? DISPLAY_UTILITY : DISPLAY_REMOTES);
         return;
      }

      if (handleNavActionCc(cc, msg.getData2()))
      {
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

   private boolean handleNavActionCc(final int cc, final int value)
   {
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
         updateUtilityRainbowAnimation(kind == DISPLAY_UTILITY);
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

   private void updateUtilityRainbowAnimation(final boolean enable)
   {
      if (enable)
      {
         if (!utilityRainbowAnimationRunning)
         {
            utilityRainbowAnimationRunning = true;
            host.scheduleTask(this::tickUtilityRainbowAnimation, UTILITY_RAINBOW_ANIMATION_MS);
         }
      }
      else
      {
         utilityRainbowAnimationRunning = false;
      }
   }

   private void tickUtilityRainbowAnimation()
   {
      if (!utilityRainbowAnimationRunning || ledDisplayKind != DISPLAY_UTILITY)
      {
         utilityRainbowAnimationRunning = false;
         return;
      }

      sendVisibleState();
      host.scheduleTask(this::tickUtilityRainbowAnimation, UTILITY_RAINBOW_ANIMATION_MS);
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

      if (ledDisplayKind == DISPLAY_SENDS)
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
         SysexProtocol.WORKSPACE_FOCUS,
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
            final int[] rgb = BitwigRemoteColors.midiRgbRainbowCycle(System.currentTimeMillis());
            faderColors[i][0] = rgb[0];
            faderColors[i][1] = rgb[1];
            faderColors[i][2] = rgb[2];

            if (!isLastTouchedActive())
            {
               faderModes[i] = SysexProtocol.FADER_MODE_STANDBY;
               faderValues[i] = 0;
               continue;
            }

            final String name = lastTouchedParameter.name().get();
            faderModes[i] = VisibleStateSysex.faderModeForParameter(true, true, name);
            faderValues[i] =
               VisibleStateSysex.parameterToMidi7(lastTouchedParameter.value().get());
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

         final int[] rgb = midiRgbFromSendColor(send.sendChannelColor());
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
      utilityRainbowAnimationRunning = false;
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
