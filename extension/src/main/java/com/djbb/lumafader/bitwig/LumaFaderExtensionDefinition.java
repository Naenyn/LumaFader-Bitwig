package com.djbb.lumafader.bitwig;

import java.util.UUID;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

public class LumaFaderExtensionDefinition extends ControllerExtensionDefinition
{
   private static final UUID DRIVER_ID = UUID.fromString("c4e8f1a2-9b3d-4e71-8f05-2d1a6b7c8e9f");

   @Override
   public String getName()
   {
      return "LumaFader";
   }

   @Override
   public String getAuthor()
   {
      return "DJBB";
   }

   @Override
   public String getVersion()
   {
      return "0.1.0";
   }

   @Override
   public UUID getId()
   {
      return DRIVER_ID;
   }

   @Override
   public String getHardwareVendor()
   {
      return "DJBB";
   }

   @Override
   public String getHardwareModel()
   {
      return "LumaFader";
   }

   @Override
   public int getRequiredAPIVersion()
   {
      return 21;
   }

   @Override
   public int getNumMidiInPorts()
   {
      return 1;
   }

   @Override
   public int getNumMidiOutPorts()
   {
      return 1;
   }

   @Override
   public void listAutoDetectionMidiPortNames(
      final AutoDetectionMidiPortNamesList list,
      final PlatformType platformType)
   {
      list.add(new String[] {"LumaFader"}, new String[] {"LumaFader"});
   }

   @Override
   public LumaFaderExtension createInstance(final ControllerHost host)
   {
      return new LumaFaderExtension(this, host);
   }
}
