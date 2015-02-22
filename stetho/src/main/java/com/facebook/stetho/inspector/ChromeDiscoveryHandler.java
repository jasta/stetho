package com.facebook.stetho.inspector;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import com.facebook.stetho.common.Utf8Charset;
import com.facebook.stetho.server.LocalSocketHttpServer;
import com.facebook.stetho.server.SecureHttpRequestHandler;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandlerRegistry;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * Provides sufficient responses to convince Chrome's {@code chrome://inspect/devices} that we're
 * "one of them".  Note that we are being discovered automatically by the name of our socket
 * as defined in {@link LocalSocketHttpServer}.  After discovery, we're required to provide
 * some context on how exactly to display and inspect what we have.
 */
public class ChromeDiscoveryHandler extends SecureHttpRequestHandler {
  private static final String PAGE_ID = "1";

  private static final String PATH_PAGE_LIST = "/json";
  private static final String PATH_VERSION = "/json/version";
  private static final String PATH_ACTIVATE = "/json/activate/" + PAGE_ID;
  private static final String PATH_FAVICON = "/favicon.png";

  /**
   * Latest version of the WebKit Inspector UI that we've tested again (ideally).
   */
  private static final String WEBKIT_REV = "@188492";
  private static final String WEBKIT_VERSION = "537.36 (" + WEBKIT_REV + ")";

  private static final String USER_AGENT = "Stetho";

  /**
   * Structured version of the WebKit Inspector protocol that we understand.
   */
  private static final String PROTOCOL_VERSION = "1.1";

  private final Context mContext;
  private final String mInspectorPath;

  @Nullable private StringEntity mVersionResponse;
  @Nullable private StringEntity mPageListResponse;
  @Nullable private ByteArrayEntity mFaviconResponse;

  public ChromeDiscoveryHandler(Context context, String inspectorPath) {
    super(context);
    mContext = context;
    mInspectorPath = inspectorPath;
  }

  public void register(HttpRequestHandlerRegistry registry) {
    registry.register(PATH_PAGE_LIST, this);
    registry.register(PATH_VERSION, this);
    registry.register(PATH_ACTIVATE + "*", this);
    registry.register(PATH_FAVICON, this);
  }

  @Override
  protected void handleSecured(
      HttpRequest request,
      HttpResponse response,
      HttpContext context)
      throws HttpException, IOException {
    Uri uri = Uri.parse(request.getRequestLine().getUri());
    String path = uri.getPath();
    try {
      if (PATH_VERSION.equals(path)) {
        handleVersion(response);
      } else if (PATH_PAGE_LIST.equals(path)) {
        handlePageList(response);
      } else if (PATH_ACTIVATE.equals(path)) {
        handleActivate(response);
      } else if (PATH_FAVICON.equals(path)) {
        handleFavicon(response);
      } else {
        response.setStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
        response.setReasonPhrase("Not Implemented");
        response.setEntity(new StringEntity("No support for " + uri.getPath()));
      }
    } catch (JSONException e) {
      response.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
      response.setReasonPhrase("Internal Server Error");
      response.setEntity(new StringEntity(e.toString(), Utf8Charset.NAME));
    }
  }

  private void handleVersion(HttpResponse response)
      throws JSONException, UnsupportedEncodingException {
    if (mVersionResponse == null) {
      JSONObject reply = new JSONObject();
      reply.put("WebKit-Version", WEBKIT_VERSION);
      reply.put("User-Agent", USER_AGENT);
      reply.put("Protocol-Version", PROTOCOL_VERSION);
      reply.put("Browser", getAppLabelAndVersion());
      reply.put("Android-Package", mContext.getPackageName());
      mVersionResponse = createStringEntity("application/json", reply.toString());
    }
    setSuccessfulResponse(response, mVersionResponse);
  }

  private void handlePageList(HttpResponse response)
      throws JSONException, UnsupportedEncodingException {
    if (mPageListResponse == null) {
      JSONArray reply = new JSONArray();
      JSONObject page = new JSONObject();
      page.put("type", "app");
      page.put("title", getAppLabel() + " (powered by Stetho)");
      page.put("id", PAGE_ID);
      page.put("description", "");
      page.put("faviconUrl", PATH_FAVICON);

      page.put("webSocketDebuggerUrl", "ws://" + mInspectorPath);
      Uri chromeFrontendUrl = new Uri.Builder()
          .scheme("http")
          .authority("chrome-devtools-frontend.appspot.com")
          .appendEncodedPath("serve_rev")
          .appendEncodedPath(WEBKIT_REV)
          .appendEncodedPath("devtools.html")
          .appendQueryParameter("ws", mInspectorPath)
          .build();
      page.put("devtoolsFrontendUrl", chromeFrontendUrl.toString());

      reply.put(page);
      mPageListResponse = createStringEntity("application/json", reply.toString());
    }
    setSuccessfulResponse(response, mPageListResponse);
  }

  private void handleActivate(HttpResponse response) throws UnsupportedEncodingException {
    // Arbitrary response seem acceptable :)
    setSuccessfulResponse(response, createStringEntity("text/plain", "Target activation ignored"));
  }

  private void handleFavicon(HttpResponse response) {
    if (mFaviconResponse == null) {
      Bitmap iconAsBitmap = getBitmapFromDrawable(getAppIcon());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      iconAsBitmap.compress(
          Bitmap.CompressFormat.PNG,
          90 /* quality */,
          out);
      ByteArrayEntity entity = new ByteArrayEntity(out.toByteArray());
      entity.setContentEncoding("image/png");
      mFaviconResponse = entity;
    }
    setSuccessfulResponse(response, mFaviconResponse);
  }

  private static Bitmap getBitmapFromDrawable(Drawable d) {
    if (d instanceof BitmapDrawable) {
      return ((BitmapDrawable)d).getBitmap();
    } else {
      int width;
      int height;
      if (d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
        width = d.getIntrinsicWidth();
        height = d.getIntrinsicHeight();
      } else {
        throw new IllegalArgumentException("Must use drawables with intrinsic dimenions");
      }
      Bitmap bitmap = Bitmap.createBitmap(
          width,
          height,
          Bitmap.Config.ARGB_4444);
      Canvas c = new Canvas(bitmap);
      d.draw(c);
      return bitmap;
    }
  }

  private static StringEntity createStringEntity(String contentType, String responseJson)
      throws UnsupportedEncodingException {
    StringEntity entity = new StringEntity(responseJson, Utf8Charset.NAME);
    entity.setContentType(contentType);
    return entity;
  }

  private static void setSuccessfulResponse(
      HttpResponse response,
      HttpEntity entity) {
    response.setStatusCode(HttpStatus.SC_OK);
    response.setReasonPhrase("OK");
    response.setEntity(entity);
  }

  private String getAppLabelAndVersion() {
    StringBuilder b = new StringBuilder();
    PackageManager pm = mContext.getPackageManager();
    b.append(getAppLabel());
    b.append('/');
    try {
      PackageInfo info = pm.getPackageInfo(mContext.getPackageName(), 0 /* flags */);
      b.append(info.versionName);
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }
    return b.toString();
  }

  private CharSequence getAppLabel() {
    PackageManager pm = mContext.getPackageManager();
    return pm.getApplicationLabel(mContext.getApplicationInfo());
  }

  private Drawable getAppIcon() {
    PackageManager pm = mContext.getPackageManager();
    return pm.getApplicationIcon(mContext.getApplicationInfo());
  }
}
