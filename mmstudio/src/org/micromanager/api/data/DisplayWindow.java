package org.micromanager.api.data;

import ij.ImagePlus;

import java.awt.Component;

/**
 * A DisplayWindow is the interface to Micro-Manager's custom image display
 * windows.
 */
public interface DisplayWindow {
   /**
    * Display the image at the specified coordinates in the Datastore the
    * display is fronting.
    */
   public void setDisplayedImageTo(Coords coords);

   /**
    * Add an additional "mode button" to the display window, to show/hide
    * the provided Component when clicked.
    */
   public void addControlPanel(String label, Component widget);

   /**
    * Retrieve the ImageJ ImagePlus object.
    */
   public ImagePlus getImagePlus();

   /**
    * Retrieve the Datastore backing this display.
    */
   public Datastore getDatastore();

   /**
    * Close the display and remove it from the Datastore.
    */
   public void close();
}
