package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.Device;
import com.bitwig.extension.controller.api.DeviceBank;
import com.bitwig.extension.controller.api.Parameter;
import com.bitwig.extension.controller.api.PinnableCursorDevice;

import java.util.function.IntConsumer;

/** Focus mode: track and device-chain navigation chords. */
final class FocusNavigation
{
   private final ControllerHost host;
   private final CursorTrack cursorTrack;
   private final PinnableCursorDevice cursorDevice;
   private final DeviceBank trackDeviceBank;
   private final DeviceBank siblingDeviceBank;
   private final Parameter[] deviceRemoteParameters;
   private final Parameter[] trackRemoteParameters;
   private final FocusNavigationState state;
   private final IntConsumer sendNavReject;
   private final Runnable onNavigationCompleted;
   private final Runnable scheduleVisibleStateUpdate;

   FocusNavigation(
      final ControllerHost host,
      final CursorTrack cursorTrack,
      final PinnableCursorDevice cursorDevice,
      final DeviceBank trackDeviceBank,
      final DeviceBank siblingDeviceBank,
      final Parameter[] deviceRemoteParameters,
      final Parameter[] trackRemoteParameters,
      final FocusNavigationState state,
      final IntConsumer sendNavReject,
      final Runnable onNavigationCompleted,
      final Runnable scheduleVisibleStateUpdate)
   {
      this.host = host;
      this.cursorTrack = cursorTrack;
      this.cursorDevice = cursorDevice;
      this.trackDeviceBank = trackDeviceBank;
      this.siblingDeviceBank = siblingDeviceBank;
      this.deviceRemoteParameters = deviceRemoteParameters;
      this.trackRemoteParameters = trackRemoteParameters;
      this.state = state;
      this.sendNavReject = sendNavReject;
      this.onNavigationCompleted = onNavigationCompleted;
      this.scheduleVisibleStateUpdate = scheduleVisibleStateUpdate;
   }

   boolean handleNavActionCc(final int cc, final int value)
   {
      if (value < 64)
      {
         return cc == LumaFaderConstants.NAV_NEXT_TRACK_CC
            || cc == LumaFaderConstants.NAV_PREV_TRACK_CC
            || cc == LumaFaderConstants.NAV_NEXT_DEVICE_CC
            || cc == LumaFaderConstants.NAV_PREV_DEVICE_CC;
      }

      switch (cc)
      {
         case LumaFaderConstants.NAV_NEXT_TRACK_CC:
            navigateTrack(1);
            return true;
         case LumaFaderConstants.NAV_PREV_TRACK_CC:
            navigateTrack(-1);
            return true;
         case LumaFaderConstants.NAV_NEXT_DEVICE_CC:
            navigateDevice(1);
            return true;
         case LumaFaderConstants.NAV_PREV_DEVICE_CC:
            navigateDevice(-1);
            return true;
         default:
            return false;
      }
   }

   void onCursorDeviceNameChanged()
   {
      if (state.syncingTrackRemotesUi)
      {
         scheduleVisibleStateUpdate.run();
         return;
      }
      if (state.editingTrackRemotes
         && isOnTrackChainDevice()
         && !isOnFirstDeviceInChain())
      {
         state.editingTrackRemotes = false;
      }
      scheduleVisibleStateUpdate.run();
   }

   private void navigateTrack(final int direction)
   {
      if (!cursorTrack.exists().get())
      {
         sendNavReject.accept(
            direction > 0 ? SysexProtocol.EDGE_BOTTOM : SysexProtocol.EDGE_TOP);
         return;
      }

      final boolean canMove =
         direction > 0
            ? cursorTrack.hasNext().get()
            : cursorTrack.hasPrevious().get();
      if (!canMove)
      {
         sendNavReject.accept(
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

      host.scheduleTask(
         () -> {
            focusCursorTrackInUi();
            if (state.editingTrackRemotes)
            {
               syncTrackRemotesUi();
               onNavigationCompleted.run();
               return;
            }
            host.scheduleTask(
               () -> {
                  cursorTrack.selectFirstChild();
                  scheduleSelectFirstDeviceAfterTrackNav();
               },
               LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
         },
         LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
   }

   private void scheduleSelectFirstDeviceAfterTrackNav()
   {
      scheduleSelectFirstDeviceAttempt(0, true);
   }

   void scheduleSelectFirstDeviceOnCursorTrack()
   {
      scheduleSelectFirstDeviceAttempt(0, false);
   }

   private void scheduleSelectFirstDeviceAttempt(final int attempt, final boolean finishNav)
   {
      host.scheduleTask(
         () -> {
            final boolean ready = ensureDeviceSelectedOnCursorTrack(attempt);
            if (ready || attempt + 1 >= LumaFaderConstants.DEVICE_SELECT_MAX_ATTEMPTS)
            {
               if (finishNav)
               {
                  onNavigationCompleted.run();
               }
               return;
            }
            scheduleSelectFirstDeviceAttempt(attempt + 1, finishNav);
         },
         LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
   }

   private boolean ensureDeviceSelectedOnCursorTrack(final int attempt)
   {
      if (!cursorTrack.exists().get())
      {
         return false;
      }

      if (attempt == 0)
      {
         focusCursorTrackInUi();
         trackDeviceBank.scrollPosition().set(0);
         trackDeviceBank.scrollIntoView(0);
         cursorTrack.selectFirstChild();
      }

      state.editingTrackRemotes = false;

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

      if (attempt == 0 || attempt + 1 >= LumaFaderConstants.DEVICE_SELECT_MAX_ATTEMPTS)
      {
         applyDeviceRemotesUi(target);
      }

      if (isRemoteTargetReady())
      {
         return true;
      }

      if (state.cursorDeviceExists())
      {
         return attempt + 1 >= LumaFaderConstants.DEVICE_SELECT_MAX_ATTEMPTS;
      }

      return false;
   }

   private boolean isRemoteTargetReady()
   {
      if (state.editingTrackRemotes)
      {
         return cursorTrack.exists().get()
            && hasActiveRemoteSlot(trackRemoteParameters);
      }
      return state.cursorDeviceExists()
         && hasActiveRemoteSlot(deviceRemoteParameters);
   }

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
      for (int i = 0; i < LumaFaderConstants.TRACK_DEVICE_BANK_MARK; i++)
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
         sendNavReject.accept(
            direction > 0 ? SysexProtocol.EDGE_RIGHT : SysexProtocol.EDGE_LEFT);
         return;
      }

      navigateDeviceAfterEnsure(direction);
   }

   private void navigateDeviceAfterEnsure(final int direction)
   {
      if (!cursorTrack.exists().get())
      {
         sendNavReject.accept(
            direction > 0 ? SysexProtocol.EDGE_RIGHT : SysexProtocol.EDGE_LEFT);
         return;
      }

      if (state.editingTrackRemotes)
      {
         if (direction < 0)
         {
            if (isWithinTrackRemotesEntryGracePeriod())
            {
               return;
            }
            sendNavReject.accept(SysexProtocol.EDGE_LEFT);
            return;
         }
         leaveTrackRemotesForFirstDevice();
         return;
      }

      if (!state.cursorDeviceExists())
      {
         if (direction < 0)
         {
            scheduleTrackRemotesUiAndComplete();
            return;
         }
         cursorDevice.selectFirstInChannel(cursorTrack);
         if (!state.cursorDeviceExists())
         {
            sendNavReject.accept(SysexProtocol.EDGE_RIGHT);
            return;
         }
      }

      if (direction < 0)
      {
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
            sendNavReject.accept(SysexProtocol.EDGE_RIGHT);
            return;
         }
         cursorDevice.selectNext();
      }

      host.scheduleTask(
         () -> {
            state.editingTrackRemotes = false;
            syncDeviceNavUi();
            onNavigationCompleted.run();
         },
         LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
   }

   private boolean isWithinTrackRemotesEntryGracePeriod()
   {
      return System.currentTimeMillis() - state.trackRemotesEntryStartedMs < 350;
   }

   private boolean isOnFirstDeviceInChain()
   {
      if (!state.cursorDeviceExists())
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
      if (!state.cursorDeviceExists())
      {
         return false;
      }

      final int cursorPosition = cursorDevice.position().get();
      for (int i = 0; i < LumaFaderConstants.TRACK_DEVICE_BANK_MARK; i++)
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
      state.editingTrackRemotes = false;
      state.syncingTrackRemotesUi = true;
      cursorDevice.selectFirstInChannel(cursorTrack);
      host.scheduleTask(
         () -> {
            state.syncingTrackRemotesUi = false;
            if (state.cursorDeviceExists())
            {
               syncDeviceNavUi();
               onNavigationCompleted.run();
            }
            else
            {
               state.editingTrackRemotes = true;
               sendNavReject.accept(SysexProtocol.EDGE_RIGHT);
            }
         },
         LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
   }

   private void scheduleTrackRemotesUiAndComplete()
   {
      state.trackRemotesEntryStartedMs = System.currentTimeMillis();
      host.scheduleTask(
         () -> {
            syncTrackRemotesUi();
            onNavigationCompleted.run();
         },
         LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
   }

   private void syncTrackRemotesUi()
   {
      state.editingTrackRemotes = true;
      state.syncingTrackRemotesUi = true;
      focusCursorTrackInUi();
      cursorTrack.selectChannel(cursorTrack);
      cursorTrack.selectInEditor();

      host.scheduleTask(
         () -> {
            state.syncingTrackRemotesUi = false;
            scheduleVisibleStateUpdate.run();
         },
         LumaFaderConstants.DEVICE_SELECT_DELAY_MS);
   }

   private void syncDeviceNavUi()
   {
      state.editingTrackRemotes = false;
      final Device bankDevice = findBankDeviceForCursor();
      if (bankDevice != null && bankDevice.exists().get())
      {
         bankDevice.selectInEditor();
      }
      cursorDevice.selectInEditor();
      applyDeviceRemotesUi(bankDevice);
   }

   private void focusCursorTrackInUi()
   {
      cursorTrack.selectInEditor();
      cursorTrack.selectInMixer();
      cursorTrack.makeVisibleInArranger();
      cursorTrack.makeVisibleInMixer();
   }

   private void collapseOtherDeviceRemotesOnTrack(final Device keepOpen)
   {
      final String keepName =
         keepOpen != null && keepOpen.exists().get() ? keepOpen.name().get() : "";

      for (int i = 0; i < LumaFaderConstants.TRACK_DEVICE_BANK_MARK; i++)
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

   private void applyDeviceRemotesUi(final Device active)
   {
      collapseOtherDeviceRemotesOnTrack(active);

      if (active != null && active.exists().get())
      {
         active.isRemoteControlsSectionVisible().set(true);
      }
      cursorDevice.isRemoteControlsSectionVisible().set(true);
   }

   private Device findBankDeviceForCursor()
   {
      if (!state.cursorDeviceExists())
      {
         return null;
      }

      final String cursorName = cursorDevice.name().get();
      for (int i = 0; i < LumaFaderConstants.TRACK_DEVICE_BANK_MARK; i++)
      {
         final Device device = trackDeviceBank.getDevice(i);
         if (device.exists().get() && device.name().get().equals(cursorName))
         {
            return device;
         }
      }
      return findFirstDeviceInBank(trackDeviceBank);
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
}
