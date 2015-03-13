package com.facebook.stetho.inspector.heap;

import android.annotation.TargetApi;
import android.os.Build;
import android.util.JsonWriter;
import com.android.ddmlib.AllocationInfo;
import com.facebook.stetho.common.Util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Formatter for Chrome's {@code .heapsnapshot} file format.  Full disclaimer,
 * I'm very fuzzy on the details of this format and could use some help cleaning things up :)
 * <p/>
 * For a sample generated from Chrome, open the inspector on a normal web page, click the Profiles
 * tab, then go to "Take heap snapshot" or "Record Heap allocations" and hit start.
 * <p/>
 * Note that this format is explicitly designed for heap dumps, not truly allocation records.  We're
 * abusing this by simulating a heap that only includes objects that were allocated during a
 * microscoped period of time.  This means that the resulting graph is shallow and will not
 * contain any retainers or retained size of individual nodes.
 * <p/>
 * This class uses tremendous amounts of memory and may exceed memory capacity on low end
 * devices.  Patches welcome :)
 */
@TargetApi(Build.VERSION_CODES.HONEYCOMB)
@NotThreadSafe
class ChromeHeapsnapshotWriter {
  private final String mSnapshotTitle;
  private final int mUid;
  private final Writer mRawOut;
  private final JsonWriter mJson;

  // Yes, we use a ton of memory.  Might as well eagerly allocate up to that large size to
  // reduce data copies when resizing.
  private final IndexedObjectCache<String> mStringCache = new IndexedObjectCache<String>(1024);
  private final SimpleIntArray mNodeRawData = new SimpleIntArray(1024 * NodeField.values().length);
  private final SimpleIntArray mEdgeRawData = new SimpleIntArray(1024 * EdgeField.values().length);

  private final IndexedObjectCache<StackTraceElement> mTraceFunctionCache =
      new IndexedObjectCache<StackTraceElement>(256);
  private final TraceTree mTraceTree = new TraceTree();

  private int mNodeCount;
  private int mEdgeCount;

  private boolean mEmpty = true;

  /**
   * Synthetic node we attach all allocations to.
   *
   * @see #addRootNodes
   */
  private int mAllocatedObjectsNodeIndex = -1;

  // Order and names match file format expectations on all enums.
  private static enum NodeField {
    TYPE,
    NAME,
    ID,
    SELF_SIZE,
    EDGE_COUNT,
    TRACE_NODE_ID;

    public String toString() {
      return name().toLowerCase();
    }
  }

  private static enum NodeType {
    HIDDEN,
    ARRAY,
    STRING,
    OBJECT,
    CODE,
    CLOSURE,
    REGEXP,
    NUMBER,
    NATIVE,
    SYNTHETIC,
    CONCATENATED_STRING("concatenated string"),
    SLICED_STRING("sliced string");

    private final String mFormattedName;

    private NodeType() {
      mFormattedName = name().toLowerCase();
    }

    private NodeType(String formattedName) {
      mFormattedName = formattedName;
    }

    public String getFormattedName() {
      return mFormattedName;
    }

    public String toString() {
      return getFormattedName();
    }
  }

  private static enum EdgeField {
    TYPE,
    NAME_OR_INDEX,
    TO_NODE;

    public String toString() {
      return name().toLowerCase();
    }
  }

  // Order and names match file format expectations
  private static enum EdgeType {
    CONTEXT,
    ELEMENT,
    PROPERTY,
    INTERNAL,
    HIDDEN,
    SHORTCUT,
    WEAK;

    public String toString() {
      return name().toLowerCase();
    }
  }

  private static enum TraceFunctionField {
    FUNCTION_ID,
    NAME,
    SCRIPT_NAME,
    SCRIPT_ID,
    LINE,
    COLUMN;

    public String toString() {
      return name().toLowerCase();
    }
  }

  private static enum TraceNodeField {
    ID,
    FUNCTION_INFO_INDEX,
    COUNT,
    SIZE,
    CHILDREN;

    public String toString() {
      return name().toLowerCase();
    }
  }

  public ChromeHeapsnapshotWriter(String snapshotTitle, int uid, Writer out) {
    mSnapshotTitle = snapshotTitle;
    mUid = uid;
    mRawOut = out;
    mJson = new JsonWriter(out);
  }

  public void writeAllocations(AllocationInfo[] allocations) {
    prepareNonEmpty();

    for (AllocationInfo allocation : allocations) {
      int traceNodeId = updateOrCreateTraceNodes(allocation);

      int allocationNode = addNode(
          determineNodeType(allocation),
          mStringCache.getOrAdd(allocation.getAllocatedClass()),
          mNodeCount /* nodeId; no idea what to use here? */,
          allocation.getSize(),
          traceNodeId);

      addEdge(
          EdgeType.ELEMENT,
          mAllocatedObjectsNodeIndex,
          allocationNode);
    }
  }

  private int updateOrCreateTraceNodes(AllocationInfo allocation) {
    TraceNode topOfTheStack = null;
    ArrayList<TraceNode> nodes = mTraceTree.roots;
    StackTraceElement[] elements = allocation.getStackTrace();
    int elementsN = elements.length;
    for (int i = elementsN - 1; i >= 0; i--) {
      StackTraceElement element = elements[i];
      int functionInfo = mTraceFunctionCache.getOrAdd(element);
      TraceNode node = findTraceNodeByFunction(functionInfo, nodes);
      if (node == null) {
        node = mTraceTree.createNode(functionInfo);
        nodes.add(node);
      }
      node.allocationCount++;
      node.allocationSize += allocation.getSize();
      if (i == 0) {
        topOfTheStack = node;
      }
      nodes = node.children;
    }
    return topOfTheStack.id;
  }

  @Nullable
  private static TraceNode findTraceNodeByFunction(
      int functionInfo,
      @Nullable ArrayList<TraceNode> nodes) {
    int nodesN = nodes != null ? nodes.size() : 0;
    for (int i = 0; i < nodesN; i++) {
      TraceNode traceNode = nodes.get(i);
      if (functionInfo == traceNode.functionInfo) {
        return traceNode;
      }
    }
    return null;
  }

  private int addNode(NodeType type, int name, int id, int selfSize, int traceNodeId) {
    mNodeRawData.add(type.ordinal());
    mNodeRawData.add(name);
    mNodeRawData.add(id);
    mNodeRawData.add(selfSize);
    mNodeRawData.add(0 /* edgeCount */);
    mNodeRawData.add(traceNodeId);

    int nodeIndex = mNodeCount;
    mNodeCount++;
    return nodeIndex;
  }

  private void addEdge(EdgeType type, int nameOrIndex, int toNodeIndex) {
    mEdgeRawData.add(type.ordinal());
    mEdgeRawData.add(nameOrIndex);
    mEdgeRawData.add(toNodeIndex * NodeField.values().length);
    mEdgeCount++;

    /* Update node edge count if necessary. */
    switch (type) {
      case ELEMENT:
      case HIDDEN:
      case WEAK:
        int[] nodeRaw = mNodeRawData.array();
        int nodeOffset = nameOrIndex * NodeField.values().length;
        nodeRaw[nodeOffset + NodeField.EDGE_COUNT.ordinal()]++;
        break;
    }
  }

  @Nonnull
  private static NodeType determineNodeType(AllocationInfo info) {
    if (isString(info)) {
      return NodeType.STRING;
    } else {
      // It's actually very misleading to try to fit within any of the other types when
      // translating between JavaScript and Java.  Best to just show the actual Java object
      // class names.
      return NodeType.OBJECT;
    }
  }

  private static boolean isString(AllocationInfo allocation) {
    String clazz = allocation.getAllocatedClass();
    if ("java.lang.String".equals(clazz)) {
      return true;
    }
    if ("char[]".equals(clazz)) {
      StackTraceElement[] stack = allocation.getStackTrace();
      StackTraceElement topOfStack = stack.length > 0 ? stack[0] : null;
      if (topOfStack != null) {
        if ("java.lang.String".equals(topOfStack.getClassName()) &&
            "<init>".equals(topOfStack.getMethodName())) {
          return true;
        }
        if ("java.lang.AbstractStringBuilder".equals(topOfStack.getClassName()) &&
            "<init>".equals(topOfStack.getMethodName())) {
          return true;
        }
      }
    }
    return false;
  }

  private void prepareNonEmpty() {
    if (mEmpty) {
      mEmpty = false;
      addRootNodes();
    }
  }

  public void close() throws IOException {
    boolean success = false;
    try {
      finish();
      success = true;
    } finally {
      Util.close(mJson, !success);
    }
  }

  private void finish() throws IOException {
    // We have to buffer the entire thing so that the meta data can be reflected correctly and
    // in order.  This JSON format is pretty flawed as it does indeed depend on order and
    // predetermined node/edge counts for high performing parsers.
    begin();

    writeNodes();
    writeEdges();
    writeTraceFunctionInfos();
    writeTraceTree();
    writeStrings();

    end();
  }

  /**
   * Mimic the graph structure of Chrome's internal heapsnapshot dump.  There's some amount
   * of magic in the basic structure which the DevTools UI searches for (for instance,
   * "(GC roots)") in order to make sense of the graph.  Note that in our case we really
   * are visualizing allocations which may or may not be referenced by the GC root but we have to
   * indicate that they are so they appear formatted properly in the UI which does a lot to
   * over-emphasize live objects.
   * <p/>
   * The rough structure we're going for is:
   * <pre>
   *   (synthetic) ""
   *     ==[element]==> (synthetic) "(GC roots)"
   *       ==[element]==> (synthetic) "(Allocated Objects)"
   *         ==[element]==> (object) "java.util.HashMap"
   *         ==[element]==> (object) "java.util.HashMap"
   *         ==[element]==> (object) "java.lang.Integer"
   *         ...
   * </pre>
   */
  private void addRootNodes() {
    int root = addNode(
        NodeType.SYNTHETIC,
        mStringCache.getOrAdd(""),
        mNodeCount /* id */,
        0 /* selfSize */,
        0 /* traceNodeId */);

    int gcRoots = addNode(
        NodeType.SYNTHETIC,
        mStringCache.getOrAdd("(GC roots)"),
        mNodeCount /* id */,
        0 /* selfSize */,
        0 /* traceNodeId */);
    addEdge(EdgeType.ELEMENT, root, gcRoots);

    mAllocatedObjectsNodeIndex = addNode(
        NodeType.SYNTHETIC,
        mStringCache.getOrAdd("(Allocated Objects)"),
        mNodeCount /* id */,
        0 /* selfSize */,
        0 /* traceNodeId */);
    addEdge(EdgeType.ELEMENT, gcRoots, mAllocatedObjectsNodeIndex);
  }

  private void begin() throws IOException {
    mJson.beginObject();
    mJson.name("snapshot");
    {
      mJson.beginObject();
      mJson.name("title");
      mJson.value(mSnapshotTitle);
      mJson.name("uid");
      mJson.value(mUid);
      mJson.name("node_count");
      mJson.value(mNodeCount);
      mJson.name("edge_count");
      mJson.value(mEdgeCount);
      mJson.name("trace_function_count");
      mJson.value(mTraceFunctionCache.size());
      mJson.name("meta");
      {
        mJson.beginObject();
        mJson.name("node_fields");
        {
          mJson.beginArray();
          for (NodeField nodeField : NodeField.values()) {
            mJson.value(nodeField.toString());
          }
          mJson.endArray();
        }
        mJson.name("node_types");
        {
          mJson.beginArray();
          {
            // I have no idea why there's an inner array.
            mJson.beginArray();
            for (NodeType nodeType : NodeType.values()) {
              mJson.value(nodeType.toString());
            }
            mJson.endArray();
          }
          mJson.endArray();
        }
        mJson.name("edge_fields");
        {
          mJson.beginArray();
          for (EdgeField edgeField : EdgeField.values()) {
            mJson.value(edgeField.toString());
          }
          mJson.endArray();
        }
        mJson.name("edge_types");
        {
          mJson.beginArray();
          {
            // This again; see comment in node_types.
            mJson.beginArray();
            for (EdgeType edgeType : EdgeType.values()) {
              mJson.value(edgeType.toString());
            }
            mJson.endArray();
          }
          mJson.endArray();
        }
        mJson.name("trace_function_info_fields");
        {
          mJson.beginArray();
          for (TraceFunctionField traceFunctionField : TraceFunctionField.values()) {
            mJson.value(traceFunctionField.toString());
          }
          mJson.endArray();
        }
        mJson.name("trace_node_fields");
        {
          mJson.beginArray();
          for (TraceNodeField traceNodeField : TraceNodeField.values()) {
            mJson.value(traceNodeField.toString());
          }
          mJson.endArray();
        }
        mJson.endObject();
      }
      mJson.endObject();
    }
  }

  private void end() throws IOException {
    mJson.endObject();
  }

  private void writeNodes() throws IOException {
    mRawOut.write('\n');
    mJson.name("nodes");
    {
      mJson.beginArray();
      writeRawData(mJson, mRawOut, mNodeRawData, NodeField.values().length);
      mJson.endArray();
    }
  }

  private void writeEdges() throws IOException {
    mRawOut.write('\n');
    mJson.name("edges");
    {
      mJson.beginArray();
      writeRawData(mJson, mRawOut, mEdgeRawData, EdgeField.values().length);
      mJson.endArray();
    }
  }

  private void writeTraceFunctionInfos() throws IOException {
    mRawOut.write('\n');
    mJson.name("trace_function_infos");
    {
      mJson.beginArray();
      ArrayList<StackTraceElement> traceFunctions = mTraceFunctionCache.orderedList();
      int traceFunctionsN = traceFunctions.size();
      for (int i = 0; i < traceFunctionsN; i++) {
        StackTraceElement traceFunction = traceFunctions.get(i);
        mJson.value(i); // id
        mJson.value(mStringCache.getOrAdd(traceFunction.toString())); // name
        mJson.value(mStringCache.getOrAdd(traceFunction.getFileName())); // script_name
        mJson.value(0); // script_id; what is this?
        mJson.value(traceFunction.getLineNumber()); // line
        mJson.value(0); // column; unknown
        mRawOut.write('\n');
      }
      mJson.endArray();
    }
  }

  private void writeTraceTree() throws IOException {
    mRawOut.write('\n');
    mJson.name("trace_tree");
    writeTraceNodes(mTraceTree.roots);
  }

  private void writeTraceNodes(ArrayList<TraceNode> nodes) throws IOException {
    int nodesN = nodes.size();
    mJson.beginArray();
    for (int i = 0; i < nodesN; i++) {
      TraceNode node = nodes.get(i);
      mJson.value(node.id);
      mJson.value(node.functionInfo);
      mJson.value(node.allocationCount);
      mJson.value(node.allocationSize);
      writeTraceNodes(node.children);
    }
    mJson.endArray();
  }

  private void writeStrings() throws IOException {
    mRawOut.write('\n');
    mJson.name("strings");
    {
      mJson.beginArray();
      ArrayList<String> strings = mStringCache.orderedList();
      int stringsN = strings.size();
      for (int i = 0; i < stringsN; i++) {
        mJson.value(strings.get(i));
        mRawOut.write('\n');
      }
      mJson.endArray();
    }
  }

  private static void writeRawData(
      JsonWriter writer,
      Writer rawOut,
      SimpleIntArray rawData,
      int rawDataStride)
      throws IOException {
    int[] array = rawData.array();
    int size = rawData.size();
    for (int i = 0; i < size; ) {
      for (int j = 0; j < rawDataStride; j++, i++) {
        writer.value(array[i]);
      }
      rawOut.write('\n');
    }
  }

  private static class TraceTree {
    private int totalNodeCount;
    public final ArrayList<TraceNode> roots = new ArrayList<TraceNode>();

    public TraceNode createNode(int functionInfo) {
      TraceNode node = new TraceNode();
      node.id = ++totalNodeCount;
      node.functionInfo = functionInfo;
      return node;
    }
  }

  private static class TraceNode {
    public int id;
    public int functionInfo;
    public int allocationCount;
    public int allocationSize;

    public final ArrayList<TraceNode> children = new ArrayList<TraceNode>();

    private TraceNode() {
    }
  }

  private static class IndexedObjectCache<T> {
    private final Map<T, Integer> mObjectToIndex;
    private final ArrayList<T> mIndexToObject;

    public IndexedObjectCache(int capacity) {
      mObjectToIndex = new HashMap<T, Integer>(capacity);
      mIndexToObject = new ArrayList<T>(capacity);
    }

    public int getOrAdd(T object) {
      Integer index = mObjectToIndex.get(object);
      if (index == null) {
        index = mIndexToObject.size();
        mIndexToObject.add(object);
        mObjectToIndex.put(object, index);
      }
      return index;
    }

    public int size() {
      return mIndexToObject.size();
    }

    public ArrayList<T> orderedList() {
      return mIndexToObject;
    }
  }

  private static class SimpleIntArray {
    private int[] mData;
    private int mSize;

    public SimpleIntArray(int startingCapacity) {
      mData = new int[startingCapacity];
    }

    public int[] array() {
      return mData;
    }

    public int size() {
      return mSize;
    }

    public void add(int value) {
      int newSize = mSize + 1;
      if (newSize > mData.length) {
        grow(newSize);
      }
      mData[mSize] = value;
      mSize = newSize;
    }

    private void grow(int minCapacity) {
      int currentCapacity = mData.length;
      if (currentCapacity < minCapacity) {
        int desiredCapacity = currentCapacity;
        do {
          desiredCapacity *= 2;
        } while (desiredCapacity < minCapacity);

        int[] newData = new int[desiredCapacity];
        System.arraycopy(mData, 0, newData, 0, mSize);
        mData = newData;
      }
    }
  }
}
