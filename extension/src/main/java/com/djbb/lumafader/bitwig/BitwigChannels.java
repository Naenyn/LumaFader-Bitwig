package com.djbb.lumafader.bitwig;

import com.bitwig.extension.controller.api.Channel;
import com.bitwig.extension.controller.api.Send;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.TrackBank;

/**
 * Bitwig channel/track/send accessors.
 * API 6+: use {@link com.bitwig.extension.controller.api.Bank#getItemAt(int)} — not
 * {@code getChannel}/{@code getTrack} on banks (see BitwigStudio.log stack traces).
 */
final class BitwigChannels
{
   private BitwigChannels()
   {
   }

   static Track trackAt(final TrackBank bank, final int bankIndex)
   {
      return (Track) bank.getItemAt(bankIndex);
   }

   static Send sendAt(final Channel channel, final int sendIndex)
   {
      return (Send) channel.sendBank().getItemAt(sendIndex);
   }
}
