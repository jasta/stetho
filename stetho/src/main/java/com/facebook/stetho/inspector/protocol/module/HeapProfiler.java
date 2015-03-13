// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.stetho.inspector.protocol.module;

import android.content.Context;
import android.os.SystemClock;
import com.facebook.stetho.common.LogRedirector;
import com.facebook.stetho.common.LogUtil;
import com.facebook.stetho.inspector.heap.AllocationTracker;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcException;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.protocol.JsonRpcError;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.json.ObjectMapper;
import com.facebook.stetho.json.annotation.JsonProperty;
import org.json.JSONObject;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class HeapProfiler implements ChromeDevtoolsDomain {
  private static final String TAG = "HeapProfiler";

  private static File sTrackingFile;
  private static volatile Context sContext;
  private final ObjectMapper mObjectMapper = new ObjectMapper();

  public HeapProfiler(Context context) {
    provideContext(context);
  }

  private static void provideContext(Context context) {
    sContext = context;
  }

  @ChromeDevtoolsMethod
  public void enable(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void disable(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void startTrackingHeapObjects(JsonRpcPeer peer, JSONObject params)
      throws JsonRpcException {
    StartTrackingHeapObjectsParams paramsObj =
        mObjectMapper.convertValue(params, StartTrackingHeapObjectsParams.class);
    if (!paramsObj.trackAllocations) {
      LogRedirector.w(TAG, "Assuming trackAllocations is true, maybe Chrome will be confused...");
    }
    if (!startTracking()) {
      throw new JsonRpcException(
          new JsonRpcError(
              JsonRpcError.ErrorCode.INTERNAL_ERROR,
              "Allocation tracking is already enabled",
              null /* data */));
    }
  }

  @ChromeDevtoolsMethod
  public synchronized void stopTrackingHeapObjects(JsonRpcPeer peer, JSONObject params) {
    // DO NOT COMMIT: total ridiculous hack
    LastSeenObjectIdParams firstSeen =
        LastSeenObjectIdParams.createForNow(1 /* lastSeenObjectId */);
    File heapsnapshotFile = stopTracking();

    peer.invokeMethod(
        "HeapProfiler.lastSeenObjectId",
        firstSeen,
        null /* callback */);
    peer.invokeMethod(
        "HeapProfiler.lastSeenObjectId",
        LastSeenObjectIdParams.createForNow(999999 /* lastSeenObjectId */),
        null /* callback */);

    if (heapsnapshotFile != null) {
      try {
        deliverHeapsnapshotFile(heapsnapshotFile, peer);
      } catch (IOException e) {
        LogRedirector.e(TAG, "Error sending snapshot file: " + heapsnapshotFile, e);
      } finally {
        heapsnapshotFile.delete();
      }
    }
  }

  private void deliverHeapsnapshotFile(File file, JsonRpcPeer peer) throws IOException {
    // I really don't get why Chrome sends such a strange sequence of events, but
    // I'm having a lot of trouble making things work so let's just copy them verbatim to
    // keep the DevTools UI code happy.
    int fileSize = (int)file.length();
    peer.invokeMethod(
        "HeapProfiler.reportHeapSnapshotProgress",
        ReportHeapSnapshotProgressParams.create(0, fileSize),
        null /* callback */);
    peer.invokeMethod(
        "HeapProfiler.reportHeapSnapshotProgress",
        ReportHeapSnapshotProgressParams.create(fileSize, fileSize),
        null /* callback */);
    peer.invokeMethod(
        "HeapProfiler.reportHeapSnapshotProgress",
        ReportHeapSnapshotProgressParams.createDone(fileSize),
        null /* callback */);

    FileInputStream in = new FileInputStream(file);
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      AddHeapSnapshotChunkParams params = new AddHeapSnapshotChunkParams();
      params.chunk = new String(buf, 0, n);
      peer.invokeMethod(
          "HeapProfiler.addHeapSnapshotChunk",
          params,
          null /* callback */);
    }
  }

  private static synchronized boolean startTracking() {
    if (AllocationTracker.isStarted()) {
      return false;
    }
    File file = sContext.getFileStreamPath("stetho-allocations.heapsnapshot");
    AllocationTracker.start(file.getAbsolutePath());
    sTrackingFile = file;
    return true;
  }

  @Nullable
  private static synchronized File stopTracking() {
    if (AllocationTracker.isStarted()) {
      AllocationTracker.stop();
      File trackingFile = sTrackingFile;
      sTrackingFile = null;
      return trackingFile;
    }
    return null;
  }

  private static class StartTrackingHeapObjectsParams {
    @JsonProperty
    public boolean trackAllocations;
  }

  private static class AddHeapSnapshotChunkParams {
    @JsonProperty(required = true)
    public String chunk;
  }

  private static class ReportHeapSnapshotProgressParams {
    @JsonProperty(required = true)
    public int done;

    @JsonProperty(required = true)
    public int total;

    @JsonProperty
    public Boolean finished;

    public static ReportHeapSnapshotProgressParams create(int done, int total) {
      ReportHeapSnapshotProgressParams params = new ReportHeapSnapshotProgressParams();
      params.done = done;
      params.total = total;
      params.finished = null;
      return params;
    }

    public static ReportHeapSnapshotProgressParams createDone(int total) {
      ReportHeapSnapshotProgressParams params = new ReportHeapSnapshotProgressParams();
      params.done = total;
      params.total = total;
      params.finished = true;
      return params;
    }
  }

  private static class LastSeenObjectIdParams {
    @JsonProperty(required = true)
    public int lastSeenObjectId;

    @JsonProperty(required = true)
    public Number timestamp;

    public static LastSeenObjectIdParams createForNow(int lastSeenObjectId) {
      LastSeenObjectIdParams params = new LastSeenObjectIdParams();
      params.lastSeenObjectId = lastSeenObjectId;
      params.timestamp = SystemClock.elapsedRealtime();
      return params;
    }
  }
}
