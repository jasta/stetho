package com.facebook.stetho.inspector.heap;

import android.os.Debug;
import android.os.Environment;
import com.android.ddmlib.AllocationInfo;
import com.android.ddmlib.AllocationsParser;
import com.facebook.stetho.common.LogUtil;
import com.facebook.stetho.common.Util;
import org.apache.harmony.dalvik.ddmc.DdmVmInternal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Instrumented allocation tracker similar to {@link Debug#startAllocCounting()} but
 * with the ability to inspect specific allocations.  Load using Chrome Developer Tools' Profiles
 * tab.
 */
public class AllocationTracker {
  /**
   * In addition to the Chrome {@code .heapsnapshot} format, also store the raw data
   * as it would have been sent to DDMS.  {@code ddmlib} can parse this file.
   */
  private static final boolean SAVE_RAW_DATA = true;

  private static volatile String sTrackingFilename;
  private static final AtomicInteger sProfileCount = new AtomicInteger(0);

  /**
   * Begin recording allocations, storing the results in {@code name}.
   *
   * @param nameOrPath Base name of the file, to be adjusted if necessary to store into
   *     {@link Environment#getExternalStorageDirectory()}.  A {@code .heapsnapshot}
   *     suffix will be added unless already present.
   *
   * @throws IllegalStateException Allocation tracking is already started.
   */
  public static void start(String nameOrPath) {
    signalStart(determineStoragePath(nameOrPath).getPath());
    DdmVmInternal.enableRecentAllocations(true);
    Debug.startAllocCounting();
  }

  private static File determineStoragePath(String filename) {
    if (!filename.endsWith(".heapsnapshot")) {
      filename += ".heapsnapshot";
    }
    File file = new File(filename);
    if (file.isAbsolute()) {
      return file;
    }
    return new File(Environment.getExternalStorageDirectory(), filename);
  }

  /**
   * Stop recording allocations and write the result to the file specified in
   * {@link #start(String)}.
   * <p/>
   * This method may have significant overhead, possibly suspending the VM for a long period of
   * time if there have been a large number of allocations.
   */
  public static void stop() {
    Debug.stopAllocCounting();
    int totalAllocCount = Debug.getGlobalAllocCount();
    byte[] rawData = DdmVmInternal.getRecentAllocations();
    DdmVmInternal.enableRecentAllocations(false);
    int snapshotNum = sProfileCount.incrementAndGet();
    if (SAVE_RAW_DATA) {
      saveRawDataQuietly(rawData);
    }
    AllocationInfo[] allocations = AllocationsParser.parse(ByteBuffer.wrap(rawData));
    saveChromeHeapSnapshotToSdcardQuietly(snapshotNum, allocations);
    signalStop();
    if (allocations.length < totalAllocCount) {
      LogUtil.e("Allocation tracking overrun: allocated " + totalAllocCount +
          " (max " + allocations.length + ")");
    }
    LogUtil.i("Processed " + allocations.length + " allocations");
  }

  /**
   * Determine if {@link #start} has been called without a matching {@link #stop}.
   */
  public static boolean isStarted() {
    return sTrackingFilename != null;
  }

  private static synchronized void signalStart(String filename) {
    if (sTrackingFilename != null) {
      throw new IllegalStateException("Concurrent tracing not allowed");
    }
    sTrackingFilename = filename;
  }

  private static void signalStop() {
    sTrackingFilename = null;
  }

  private static void saveRawDataQuietly(byte[] rawData) {
    String rawFilename =
        sTrackingFilename.substring(0, sTrackingFilename.length() - ".heapsnapshot".length()) +
        ".allocs";
    try {
      saveRawData(rawFilename, rawData);
    } catch (IOException e) {
      LogUtil.e(e, "Failed to write raw allocation data to %s", rawFilename);
    }
  }

  private static void saveRawData(String file, byte[] rawData) throws IOException {
    FileOutputStream out = new FileOutputStream(file);
    try {
      out.write(rawData);
    } finally {
      out.close();
    }
  }

  private static void saveChromeHeapSnapshotToSdcardQuietly(
      int snapshotNum,
      AllocationInfo[] allocations) {
    String filename = sTrackingFilename;
    try {
      saveChromeHeapSnapshotToSdcard(snapshotNum, filename, allocations);
      LogUtil.i("Wrote " + filename);
    } catch (IOException e) {
      LogUtil.e(e, "Failed to write Chrome .heapsnapshot data to %s " +
          "(do you have WRITE_EXTERNAL_STORAGE permission?)", filename);
    }
  }

  // It'll be an absolute miracle if this doesn't cause a heap crash :)
  private static void saveChromeHeapSnapshotToSdcard(
      int snapshotNum,
      String filename,
      AllocationInfo[] allocations)
      throws IOException {
    ChromeHeapsnapshotWriter writer =
        new ChromeHeapsnapshotWriter(
            "Snapshot " + snapshotNum,
            snapshotNum,
            new BufferedWriter(new FileWriter(filename)));
    try {
      writer.writeAllocations(allocations);
    } finally {
      writer.close();
    }
  }
}
