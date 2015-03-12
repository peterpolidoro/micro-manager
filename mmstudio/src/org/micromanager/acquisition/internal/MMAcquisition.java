///////////////////////////////////////////////////////////////////////////////
//FILE:          MMAcquisition.java
//PROJECT:       Micro-Manager
//SUBSYSTEM:     mmstudio
//-----------------------------------------------------------------------------
//
// AUTHOR:       Nico Stuurman, November 2010
//
// COPYRIGHT:    University of California, San Francisco, 2010
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.acquisition.internal;

import ij.ImagePlus;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.concurrent.BlockingQueue;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JToggleButton;

import mmcorej.CMMCore;
import mmcorej.TaggedImage;

import org.json.JSONObject;
import org.json.JSONException;
import org.micromanager.internal.MMStudio;

import org.micromanager.data.internal.multipagetiff.MultipageTiffReader;
import org.micromanager.data.internal.multipagetiff.StorageMultipageTiff;
import org.micromanager.data.internal.StorageSinglePlaneTiffSeries;
import org.micromanager.data.internal.StorageRAM;
import org.micromanager.data.Datastore;
import org.micromanager.data.DatastoreFrozenException;
import org.micromanager.data.Storage;
import org.micromanager.data.SummaryMetadata;

import org.micromanager.data.internal.DefaultDatastore;
import org.micromanager.data.internal.DefaultImage;
import org.micromanager.data.internal.DefaultSummaryMetadata;

import org.micromanager.internal.dialogs.AcqControlDlg;

import org.micromanager.display.ControlsFactory;
import org.micromanager.display.DisplayWindow;
import org.micromanager.display.internal.DefaultDisplayWindow;

import org.micromanager.internal.utils.JavaUtils;
import org.micromanager.internal.utils.MDUtils;
import org.micromanager.internal.utils.MMScriptException;
import org.micromanager.internal.utils.ReportingUtils;

/**
 * This class is used to execute most of the acquisition and image display
 * functionality in the ScriptInterface
 */
public class MMAcquisition {
   
   public static final Color[] DEFAULT_COLORS = {Color.red, Color.green, Color.blue,
      Color.pink, Color.orange, Color.yellow};
   
   /** 
    * Final queue of images immediately prior to insertion into the ImageCache.
    * Only used when running in asynchronous mode.
    */
   private BlockingQueue<TaggedImage> outputQueue_ = null;
   private boolean isAsynchronous_ = false;
   private int numFrames_ = 0;
   private int numChannels_ = 0;
   private int numSlices_ = 0;
   private int numPositions_ = 0;
   protected String name_;
   protected int width_ = 0;
   protected int height_ = 0;
   protected int byteDepth_ = 1;
   protected int bitDepth_ = 8;    
   protected int multiCamNumCh_ = 1;
   private boolean initialized_ = false;
   private long startTimeMs_;
   private final String comment_ = "";
   private String rootDirectory_;
   private DefaultDatastore store_;
   private DefaultDisplayWindow display_;
   private final boolean existing_;
   private final boolean virtual_;
   private AcquisitionEngine eng_;
   private final boolean show_;
   private JSONObject summary_ = new JSONObject();
   private final String NOTINITIALIZED = "Acquisition was not initialized";

   public MMAcquisition(String name, String dir) throws MMScriptException {
      this(name, dir, false, false, false);
   }

   public MMAcquisition(String name, String dir, boolean show) throws MMScriptException {
      this(name, dir, show, false, false);
   }

   public MMAcquisition(String name, String dir, boolean show,
           boolean diskCached, boolean existing) throws MMScriptException {
      name_ = name;
      rootDirectory_ = dir;
      show_ = show;
      existing_ = existing;
      virtual_ = diskCached;
   }

   public MMAcquisition(String name, JSONObject summaryMetadata, boolean diskCached, 
           AcquisitionEngine eng, boolean show) {
      name_ = name;
      virtual_ = diskCached;
      existing_ = false;
      eng_ = eng;
      show_ = show;
      store_ = new DefaultDatastore();
      MMStudio.getInstance().displays().manage(store_);
      try {
         if (summaryMetadata.has("Directory") && summaryMetadata.get("Directory").toString().length() > 0) {
            // Set up saving to the target directory.
            try {
               String acqDirectory = createAcqDirectory(summaryMetadata.getString("Directory"), summaryMetadata.getString("Prefix"));
               summaryMetadata.put("Prefix", acqDirectory);
               String acqPath = summaryMetadata.getString("Directory") + File.separator + acqDirectory;
               // TODO: respect user selection of multi-file saving method.
               store_.setStorage(new StorageMultipageTiff(store_,
                        acqPath, true));
            } catch (Exception e) {
               ReportingUtils.showError(e, "Unable to create directory for saving images.");
               eng.stop(true);
            }
         } else {
            store_.setStorage(new StorageRAM(store_));
         }
      }
      catch (JSONException e) {
         ReportingUtils.logError(e, "Couldn't adjust summary metadata.");
      }

      try {
         store_.setSummaryMetadata(DefaultSummaryMetadata.legacyFromJSON(summaryMetadata));
      }
      catch (DatastoreFrozenException e) {
         ReportingUtils.logError(e, "Couldn't set summary metadata");
      }
      if (show_) {
         display_ = new DefaultDisplayWindow(store_, makeControlsFactory());
      }
  }
   
   private String createAcqDirectory(String root, String prefix) throws Exception {
      File rootDir = JavaUtils.createDirectory(root);
      int curIndex = getCurrentMaxDirIndex(rootDir, prefix + "_");
      return prefix + "_" + (1 + curIndex);
   }

   private int getCurrentMaxDirIndex(File rootDir, String prefix) throws NumberFormatException {
      int maxNumber = 0;
      int number;
      String theName;
      for (File acqDir : rootDir.listFiles()) {
         theName = acqDir.getName();
         if (theName.startsWith(prefix)) {
            try {
               //e.g.: "blah_32.ome.tiff"
               Pattern p = Pattern.compile("\\Q" + prefix + "\\E" + "(\\d+).*+");
               Matcher m = p.matcher(theName);
               if (m.matches()) {
                  number = Integer.parseInt(m.group(1));
                  if (number >= maxNumber) {
                     maxNumber = number;
                  }
               }
            } catch (NumberFormatException e) {
            } // Do nothing.
         }
      }
      return maxNumber;
   }

   public void setImagePhysicalDimensions(int width, int height,
           int byteDepth, int bitDepth, int multiCamNumCh) throws MMScriptException {
      if (initialized_) {
         throw new MMScriptException("Can't change image dimensions - the acquisition is already initialized");
      }
      width_ = width;
      height_ = height;
      byteDepth_ = byteDepth;
      bitDepth_ = bitDepth;
      multiCamNumCh_ = multiCamNumCh;
   }

   public int getWidth() {
      return width_;
   }

   public int getHeight() {
      return height_;
   }

   public int getByteDepth() {
      return byteDepth_;
   }
   
   public int getBitDepth() {
      return bitDepth_;
   }

   public int getMultiCameraNumChannels() {
      return multiCamNumCh_;
   }

   public int getFrames() {
      return numFrames_;
   }

   public int getChannels() {
      return numChannels_;
   }

   public int getSlices() {
      return numSlices_;
   }

   public int getPositions() {
      return numPositions_;
   }
   
   public void setDimensions(int frames, int channels, int slices) throws MMScriptException {
      setDimensions(frames, channels, slices, 0);
   }

   public void setDimensions(int frames, int channels, int slices, int positions) throws MMScriptException {
      if (initialized_) {
         throw new MMScriptException("Can't change dimensions - the acquisition is already initialized");
      }
      numFrames_ = frames;
      numChannels_ = channels;
      numSlices_ = slices;
      numPositions_ = positions;
   }

   public void setRootDirectory(String dir) throws MMScriptException {
      if (initialized_) {
         throw new MMScriptException("Can't change root directory - the acquisition is already initialized");
      }
      rootDirectory_ = dir;
   }
   
   public void initialize() throws MMScriptException {
      if (initialized_) {
         throw new MMScriptException("Acquisition is already initialized");
      }

      Storage imageFileManager = null;
      String name = name_;
      
      store_ = new DefaultDatastore();
      MMStudio.getInstance().displays().manage(store_);

      if (virtual_ && existing_) {
         String dirName = rootDirectory_ + File.separator + name;
         try {
            boolean multipageTiff = MultipageTiffReader.isMMMultipageTiff(dirName);
            if (multipageTiff) {
               imageFileManager = new StorageMultipageTiff(store_, dirName,
                     false);
            } else {
               imageFileManager = new StorageSinglePlaneTiffSeries(
                     store_, dirName, false);
            }
         } catch (Exception ex) {
            ReportingUtils.showError(ex, "Failed to open file");
         }

         store_.setStorage(imageFileManager);
         store_.setSavePath(dirName);
         try {
            store_.setSummaryMetadata((new DefaultSummaryMetadata.Builder().build()));
         }
         catch (DatastoreFrozenException e) {
            ReportingUtils.logError(e, "Couldn't set summary metadata");
         }
         // Now that the datastore is set up, create the display(s).
         if (show_) {
            List<DisplayWindow> displays = MMStudio.getInstance().displays().loadDisplaySettings(
                  store_, dirName);
            if (displays.size() == 0) {
               // Just create a new default display.
               new DefaultDisplayWindow(store_, null);
            }
         }
      }

      if (virtual_ && !existing_) {
         String dirName = rootDirectory_ + File.separator + name;
         if ((new File(dirName)).exists()) {
            try {
               String acqDirectory = createAcqDirectory(rootDirectory_, name_);
               if (summary_ != null) {
                  summary_.put("Prefix", acqDirectory);
                  summary_.put("Channels", numChannels_);
                  MDUtils.setPixelTypeFromByteDepth(summary_, byteDepth_);
               }
               dirName = rootDirectory_ + File.separator + acqDirectory;
            } catch (Exception ex) {
               throw new MMScriptException("Failed to figure out acq saving path.");
            }
         }

         SummaryMetadata summary = DefaultSummaryMetadata.legacyFromJSON(summary_);
         try {
            // TODO: allow users to save in the StorageSinglePlaneTiffSeries
            // format.
            store_.setStorage(new StorageMultipageTiff(store_, dirName, true));
            store_.setSummaryMetadata(summary);
         }
         catch (java.io.IOException e) {
            ReportingUtils.showError(e, "Unable to prepare saving");
         }
         catch (DatastoreFrozenException e) {
            ReportingUtils.logError("Unable to set summary metadata; datastore pre-emptively locked. This should never happen!");
         }
      }

      if (!virtual_ && !existing_) {
         store_.setStorage(new StorageRAM(store_));
         try {
            store_.setSummaryMetadata((new DefaultSummaryMetadata.Builder().build()));
         }
         catch (DatastoreFrozenException e) {
            ReportingUtils.logError(e, "Couldn't set summary metadata");
         }
      }

      if (!virtual_ && existing_) {
         String dirName = rootDirectory_ + File.separator + name;
         Storage tempImageFileManager = null;
         boolean multipageTiff;
         try {
            multipageTiff = MultipageTiffReader.isMMMultipageTiff(dirName);
            if (multipageTiff) {
               tempImageFileManager = new StorageMultipageTiff(store_, dirName, false);
            } else {
               tempImageFileManager = new StorageSinglePlaneTiffSeries(
                     store_, dirName, false);
            }
         } catch (Exception ex) {
            ReportingUtils.showError(ex, "Failed to open file");
            return;
         }

         // Copy from the TIFF storage to a RAM-backed storage.
         store_.setStorage(tempImageFileManager);
         DefaultDatastore duplicate = new DefaultDatastore();
         duplicate.setStorage(new StorageRAM(duplicate));
         duplicate.setSavePath(dirName);
         duplicate.copyFrom(store_);
         store_ = duplicate;
         if (show_) {
            List<DisplayWindow> displays = MMStudio.getInstance().displays().loadDisplaySettings(
                  store_, dirName);
            if (displays.size() == 0) {
               // Just create a new default display.
               new DefaultDisplayWindow(store_, null);
            }
         }
         // TODO: re-implement the check below before loading images into RAM
//         imageCache_ = new MMImageCache(tempImageFileManager);
//         if (tempImageFileManager.getDataSetSize() > 0.9 * JavaUtils.getAvailableUnusedMemory()) {
//            throw new MMScriptException("Not enough room in memory for this data set.\nTry opening as a virtual data set instead.");
//         }
//         imageFileManager = new TaggedImageStorageRamFast(null);
//         imageCache_.saveAs(imageFileManager);
      }

      CMMCore core = MMStudio.getInstance().getCore();
      if (!existing_) {
         createDefaultAcqSettings();
      }

      if (store_.getSummaryMetadata() != null) {
         if (show_ && !existing_) {
            // NB pre-existing setups will have loaded saved display settings.
            display_ = new DefaultDisplayWindow(store_, makeControlsFactory());
         }
         initialized_ = true;
      }
      else {
         ReportingUtils.logError("Null summary metadata");
      }
   }

   /**
    * Generate the abort and pause buttons. These are only used for display
    * windows for ongoing acquisitions (i.e. not for opening files from
    * disk).
    * TODO: remove these special controls (or at least hide them) when the
    * acquisition ends.
    */
   private ControlsFactory makeControlsFactory() {
      return new ControlsFactory() {
         @Override
         public List<Component> makeControls(DisplayWindow display) {
            ArrayList<Component> result = new ArrayList<Component>();
            JButton abortButton = new JButton(new ImageIcon(
                     getClass().getResource("/org/micromanager/internal/icons/cancel.png")));
            abortButton.setBackground(new Color(255, 255, 255));
            abortButton.setToolTipText("Halt data acquisition");
            abortButton.setFocusable(false);
            abortButton.setMaximumSize(new Dimension(30, 28));
            abortButton.setMinimumSize(new Dimension(30, 28));
            abortButton.setPreferredSize(new Dimension(30, 28));
            abortButton.addActionListener(new ActionListener() {
               @Override
               public void actionPerformed(ActionEvent e) {
                  eng_.abortRequest();
               }
            });
            result.add(abortButton);

            JToggleButton pauseButton = new JToggleButton(new ImageIcon(
                     getClass().getResource("/org/micromanager/internal/icons/control_pause.png")));
            ImageIcon icon = new ImageIcon(getClass().getResource(
                  "/org/micromanager/internal/icons/resultset_next.png"));
            pauseButton.setPressedIcon(icon);
            pauseButton.setSelectedIcon(icon);
            pauseButton.setToolTipText("Pause data acquisition");
            pauseButton.setFocusable(false);
            pauseButton.setMaximumSize(new Dimension(30, 28));
            pauseButton.setMinimumSize(new Dimension(30, 28));
            pauseButton.setPreferredSize(new Dimension(30, 28));
            pauseButton.addActionListener(new ActionListener() {
               @Override
               public void actionPerformed(ActionEvent e) {
                  eng_.setPause(!eng_.isPaused());
               }
            });
            result.add(pauseButton);

            return result;
         }
      };
   }
  
   private void createDefaultAcqSettings() {
      String keys[] = new String[summary_.length()];
      Iterator<String> it = summary_.keys();
      int i = 0;
      while (it.hasNext()) {
         keys[0] = it.next();
         i++;
      }

      try {
         JSONObject summaryMetadata = new JSONObject(summary_, keys);
         CMMCore core = MMStudio.getInstance().getCore();

         summaryMetadata.put("BitDepth", bitDepth_);
         summaryMetadata.put("Channels", numChannels_);
         // TODO: set channel name, color, min, max from defaults.
         summaryMetadata.put("Comment", comment_);
         String compName = null;
         try {
            compName = InetAddress.getLocalHost().getHostName();
         } catch (UnknownHostException e) {
            ReportingUtils.showError(e);
         }
         if (compName != null) {
            summaryMetadata.put("ComputerName", compName);
         }
         summaryMetadata.put("Date", new SimpleDateFormat("yyyy-MM-dd").format(Calendar.getInstance().getTime()));
         summaryMetadata.put("Depth", core.getBytesPerPixel());
         summaryMetadata.put("Frames", numFrames_);
         summaryMetadata.put("GridColumn", 0);
         summaryMetadata.put("GridRow", 0);
         summaryMetadata.put("Height", height_);
         int ijType = -1;
         if (byteDepth_ == 1) {
            ijType = ImagePlus.GRAY8;
         } else if (byteDepth_ == 2) {
            ijType = ImagePlus.GRAY16;
         } else if (byteDepth_ == 8) {
            ijType = 64;
         } else if (byteDepth_ == 4 && core.getNumberOfComponents() == 1) {
            ijType = ImagePlus.GRAY32;
         } else if (byteDepth_ == 4 && core.getNumberOfComponents() == 4) {
            ijType = ImagePlus.COLOR_RGB;
         }
         summaryMetadata.put("IJType", ijType);
         summaryMetadata.put("MetadataVersion", 10);
         summaryMetadata.put("MicroManagerVersion", MMStudio.getInstance().getVersion());
         summaryMetadata.put("NumComponents", 1);
         summaryMetadata.put("Positions", numPositions_);
         summaryMetadata.put("Source", "Micro-Manager");
         summaryMetadata.put("PixelAspect", 1.0);
         summaryMetadata.put("PixelSize_um", core.getPixelSizeUm());
         summaryMetadata.put("PixelType", (core.getNumberOfComponents() == 1 ? "GRAY" : "RGB") + (8 * byteDepth_));
         summaryMetadata.put("Slices", numSlices_);
         summaryMetadata.put("SlicesFirst", false);
         summaryMetadata.put("StartTime", MDUtils.getCurrentTime());
         summaryMetadata.put("Time", Calendar.getInstance().getTime());
         summaryMetadata.put("TimeFirst", true);
         summaryMetadata.put("UserName", System.getProperty("user.name"));
         summaryMetadata.put("UUID", UUID.randomUUID());
         summaryMetadata.put("Width", width_);
         startTimeMs_ = System.currentTimeMillis();
         try {
            store_.setSummaryMetadata(DefaultSummaryMetadata.legacyFromJSON(summaryMetadata));
         }
         catch (DatastoreFrozenException e) {
            ReportingUtils.logError(e, "Couldn't set summary metadata");
         }
      } catch (JSONException ex) {
         ReportingUtils.showError(ex);
      }
   }
   
   public static int getMultiCamDefaultChannelColor(int index, String channelName) {
      int color = DEFAULT_COLORS[index % DEFAULT_COLORS.length].getRGB();
      String channelGroup = MMStudio.getInstance().getCore().getChannelGroup();
      if (channelGroup == null)
         channelGroup = "";
      color = AcqControlDlg.getChannelColor("Camera", channelName,
            AcqControlDlg.getChannelColor(channelGroup, channelName, color));
      return color;
   }

   // Somebody please comment on why this is a separate method from insertImage.
   public void insertTaggedImage(TaggedImage taggedImg, int frame, int channel, int slice)
           throws MMScriptException {
      if (!initialized_) {
         throw new MMScriptException("Acquisition data must be initialized before inserting images");
      }

      // update acq data
      try {
         JSONObject tags = taggedImg.tags;

         MDUtils.setFrameIndex(tags, frame);
         MDUtils.setChannelIndex(tags, channel);
         MDUtils.setSliceIndex(tags, slice);
         MDUtils.setPixelTypeFromByteDepth(tags, byteDepth_);
         MDUtils.setPositionIndex(tags, 0);
         insertImage(taggedImg);
      } catch (JSONException e) {
         throw new MMScriptException(e);
      }
   }

   public void insertImage(TaggedImage taggedImg, int frame, int channel, int slice,
           int position) throws MMScriptException, JSONException {
      JSONObject tags = taggedImg.tags;
      MDUtils.setFrameIndex(tags, frame);
      MDUtils.setChannelIndex(tags, channel);
      MDUtils.setSliceIndex(tags, slice);
      MDUtils.setPositionIndex(tags, position);
      insertImage(taggedImg, show_);
   }

   public void insertImage(TaggedImage taggedImg, int frame, int channel, int slice,
           int position, boolean updateDisplay) throws MMScriptException, JSONException {
      JSONObject tags = taggedImg.tags;
      MDUtils.setFrameIndex(tags, frame);
      MDUtils.setChannelIndex(tags, channel);
      MDUtils.setSliceIndex(tags, slice);
      MDUtils.setPositionIndex(tags, position);
      insertImage(taggedImg, updateDisplay, true);
   }

   public void insertImage(TaggedImage taggedImg, int frame, int channel, int slice,
           int position, boolean updateDisplay, boolean waitForDisplay) throws MMScriptException, JSONException {
      JSONObject tags = taggedImg.tags;
      MDUtils.setFrameIndex(tags, frame);
      MDUtils.setChannelIndex(tags, channel);
      MDUtils.setSliceIndex(tags, slice);
      MDUtils.setPositionIndex(tags, position);
      insertImage(taggedImg, updateDisplay, waitForDisplay);
   }

   public void insertImage(TaggedImage taggedImg) throws MMScriptException {
      insertImage(taggedImg, show_);
   }

   public void insertImage(TaggedImage taggedImg, boolean updateDisplay) throws MMScriptException {
      insertImage(taggedImg, updateDisplay && show_ , true);
   }

   /*
    * This is the insertImage version that actually puts data into the acquisition
    */
   public void insertImage(TaggedImage taggedImg,
           boolean updateDisplay,
           boolean waitForDisplay) throws MMScriptException {
      if (!initialized_) {
         throw new MMScriptException("Acquisition data must be initialized before inserting images");
      }

      try {
         JSONObject tags = taggedImg.tags;

         if (!(MDUtils.getWidth(tags) == width_
                 && MDUtils.getHeight(tags) == height_)) {
            ReportingUtils.logError("Metadata width and height: " + MDUtils.getWidth(tags) + "  "
                    + MDUtils.getHeight(tags) + "   Acquisition Width and height: " + width_ + " "
                    + height_);
            throw new MMScriptException("Image dimensions do not match MMAcquisition.");
         }
         if (!MDUtils.getPixelType(tags).contentEquals(getPixelType(byteDepth_))) {
            throw new MMScriptException("Pixel type does not match MMAcquisition.");
         }

         long elapsedTimeMillis = System.currentTimeMillis() - startTimeMs_;
         MDUtils.setElapsedTimeMs(tags, elapsedTimeMillis);
         MDUtils.setImageTime(tags, MDUtils.getCurrentTime());
      } catch (IllegalStateException ex) {
         throw new MMScriptException(ex);
      } catch (JSONException ex) {
         throw new MMScriptException(ex);
      } catch (MMScriptException ex) {
         throw new MMScriptException(ex);
      }

      try {
         DefaultImage image = new DefaultImage(taggedImg);
         image.splitMultiComponentIntoStore(store_);
      }
      catch (JSONException e) {
         ReportingUtils.logError(e, "Couldn't generate DefaultImage from TaggedImage.");
      }
      catch (DatastoreFrozenException e) {
         ReportingUtils.showError(e, "Couldn't insert image into datastore.");
      }
   }

   public void close() {
      if (display_ != null) {
         display_.requestToClose();
      }
//      store_.lock();
   }

   public boolean isInitialized() {
      return initialized_;
   }

   public void promptToSave(boolean promptToSave) {
      ReportingUtils.logError("TODO: Prompt to save!");
   }

   /**
    * Tests whether the window associated with this acquisition is closed
    * 
    * @return true when acquisition has an open window, false otherwise 
    */
   public boolean windowClosed() {
      if (!show_ || !initialized_) {
         return false;
      }
      if (display_ != null && !display_.getIsClosed()) {
         return true;
      }
      return false;
   }
   
   /**
    * Returns show flag, indicating whether this acquisition was opened with
    * a request to show the image in a window
    * 
    * @return flag for request to display image in window
    */
   public boolean getShow() {
      return show_;
   }

   private static String getPixelType(int depth) {
      switch (depth) {
         case 1:
            return "GRAY8";
         case 2:
            return "GRAY16";
         case 4:
            return "RGB32";
         case 8:
            return "RGB64";
      }
      return null;
   }

   public int getLastAcquiredFrame() {
      return store_.getAxisLength("time");
   }

   public Datastore getDatastore() {
      return store_;
   }

   public void setAsynchronous() {
      isAsynchronous_ = true;
   }
}
