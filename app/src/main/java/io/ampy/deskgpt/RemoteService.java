package io.ampy.deskgpt;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteService extends AccessibilityService {
  public static volatile RemoteService active;
  private volatile byte[] screenshot;
  private final AtomicBoolean pending = new AtomicBoolean(false);
  @Override protected void onServiceConnected() { active=this; }
  @Override public void onAccessibilityEvent(AccessibilityEvent ev) {}
  @Override public void onInterrupt() {}
  @Override public void onDestroy() { active=null; super.onDestroy(); }
  public boolean tap(float x, float y) {
    int w=getResources().getDisplayMetrics().widthPixels;
    int h=getResources().getDisplayMetrics().heightPixels;
    Path path=new Path(); path.moveTo(Math.max(0,Math.min(w-1,x*w)),Math.max(0,Math.min(h-1,y*h)));
    GestureDescription gesture=new GestureDescription.Builder()
       .addStroke(new GestureDescription.StrokeDescription(path,0,90)).build();
    return dispatchGesture(gesture,null,null);
  }
  public boolean action(String name) {
    switch(name) {
      case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
      case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
      case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
      case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
      default: return false;
    }
  }
  public byte[] lastScreenshot() {
    if(pending.compareAndSet(false,true)) {
      try {
        takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback() {
          @Override public void onSuccess(ScreenshotResult result) {
            try {
              HardwareBuffer buffer=result.getHardwareBuffer();
              Bitmap bitmap=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
              if(bitmap!=null) {
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG,65,output);
                screenshot=output.toByteArray(); bitmap.recycle();
              }
              buffer.close();
            } catch(Exception ignored) {} finally { pending.set(false); }
          }
          @Override public void onFailure(int code) { screenshot=null; pending.set(false); }
        });
      } catch(Exception e) { pending.set(false); }
    }
    return screenshot;
  }
}
