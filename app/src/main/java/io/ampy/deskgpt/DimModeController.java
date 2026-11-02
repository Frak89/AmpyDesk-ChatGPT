package io.ampy.deskgpt;

import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.FrameLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Experimental non-root Stealth Display Mode.
 * Display stays ON/interactive; physical brightness goes to documented minimum (1/255).
 * Full-screen transparent overlay consumes FIRST physical touch to restore brightness.
 * The overlay is temporarily removed around approved PC remote taps.
 *
 * NOT equivalent to physically disconnecting / switching off the OLED panel.
 */
public final class DimModeController {
  private static final String PREF="ampydesk_dim";
  private static final Handler MAIN=new Handler(Looper.getMainLooper());
  private static final long MAX_DURATION_MS=2L*60*60*1000; // automatically restore after two hours
  private static boolean active=false;
  private static Context context;
  private static PowerManager.WakeLock displayLock;
  private static WindowManager windowManager;
  private static FrameLayout touchShield;
  private static BroadcastReceiver screenOffReceiver;
  private static int originalBrightness=-1;
  private static int originalMode=-1;
  private static long generation=0;
  private static volatile long lastRemoteTap=0;
  private DimModeController(){}
  public static boolean isActive(){return active;}
  public static boolean permissions(Context c) {
    return Settings.System.canWrite(c) && Settings.canDrawOverlays(c);
  }
  public static void requestWriteSettings(Context c) {
    Intent intent=new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
      Uri.parse("package:"+c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    c.startActivity(intent);
  }
  public static void requestOverlay(Context c) {
    Intent intent=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
      Uri.parse("package:"+c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    c.startActivity(intent);
  }
  /**
   * If Android killed the process without onDestroy, restore the brightness saved before dimming.
   * Do NOT require the old MediaProjection session to restart after a crash.
   */
  public static synchronized void recoverIfNeeded(Context c) {
    if(active)return;
    var prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    if(!prefs.getBoolean("active",false))return;
    if(Settings.System.canWrite(c)) {
      try {
        Settings.System.putInt(c.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,
          prefs.getInt("brightness",110));
        Settings.System.putInt(c.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,
          prefs.getInt("mode",Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC));
      }catch(Exception e){android.util.Log.w("AmpyDesk","Cannot recover brightness",e);return;}
    } else return;
    prefs.edit().putBoolean("active",false).apply();
  }
  public static synchronized boolean enter(Context source) {
    if(active)return true;
    if(!permissions(source))return false;
    if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");
    Context c=source.getApplicationContext();
    try {
      int now=Settings.System.getInt(c.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS);
      int mode=Settings.System.getInt(c.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE);
      originalBrightness=now;originalMode=mode;context=c;
      c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
        .putInt("brightness",now).putInt("mode",mode).putBoolean("active",true).commit();
      PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
      // This legacy wake-lock is still exposed but OEMs may behave differently.
      displayLock=pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,"AmpyDeskGPT:StealthDisplay");
      displayLock.acquire(MAX_DURATION_MS+1500);
      Settings.System.putInt(c.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
      Settings.System.putInt(c.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,1);
      active=true;
      generation++;
      createShield(c);
      IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
      screenOffReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){exit();}
      };
      c.registerReceiver(screenOffReceiver,filter);
      long cycle=generation;
      MAIN.postDelayed(()->{if(active && generation==cycle)exit();},MAX_DURATION_MS);
      return true;
    }catch(Exception e){
      android.util.Log.e("AmpyDesk","Stealth Display Mode failed",e);
      exit();
      return false;
    }
  }
  private static void createShield(Context c) {
    if(!active || touchShield!=null || !Settings.canDrawOverlays(c))return;
    windowManager=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
    FrameLayout shield=new FrameLayout(c);
    shield.setBackgroundColor(Color.TRANSPARENT);
    shield.setContentDescription("Tap to restore AmpyDesk display brightness");
    shield.setOnTouchListener((v,event)->{
      if(event.getAction()==MotionEvent.ACTION_DOWN){exit();}
      return true;
    });
    WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT);
    lp.gravity=Gravity.TOP | Gravity.START;lp.setTitle("AmpyDeskGPT Stealth Touch to Restore");
    windowManager.addView(shield,lp);
    touchShield=shield;
  }
  private static void clearShield() {
    if(touchShield!=null&&windowManager!=null) {
      try{windowManager.removeViewImmediate(touchShield);}catch(Exception ignored){}
    }
    touchShield=null;
  }
  /**
   * The PC can keep tapping Android applications without the invisible phone overlay
   * intercepting every gesture. Screen does not brighten for PC-originated taps.
   * Blocking briefly here is deliberate: do not race the UI thread's removeView.
   */
  public static void beforeRemoteTap() {
    if(!active)return;
    lastRemoteTap=SystemClock.elapsedRealtime();
    if(Looper.myLooper()==Looper.getMainLooper()) {
      clearShield();
      scheduleShield();
    } else {
      CountDownLatch latch=new CountDownLatch(1);
      MAIN.post(()->{clearShield();scheduleShield();latch.countDown();});
      try{latch.await(350,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
  }
  private static void scheduleShield() {
    long cycle=generation;
    MAIN.postDelayed(()->{
      if(!active || generation!=cycle)return;
      if(SystemClock.elapsedRealtime()-lastRemoteTap<1050) {
        scheduleShield();
        return;
      }
      if(touchShield==null && context!=null) {
        try{createShield(context);}catch(Exception e){android.util.Log.w("AmpyDesk","Shield restore failed",e);}
      }
    },1100);
  }
  public static synchronized void exit() {
    generation++;
    // all window changes go through main thread. Usually triggered from phone Activity or overlay.
    if(Looper.myLooper()!=Looper.getMainLooper())MAIN.post(DimModeController::clearShield);
    else clearShield();
    if(context!=null) {
      if(screenOffReceiver!=null) {
        try{context.unregisterReceiver(screenOffReceiver);}catch(Exception ignored){}
      }
      screenOffReceiver=null;
      try {
        if(Settings.System.canWrite(context)){
          int value=originalBrightness;
          int mode=originalMode;
          var prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
          if(value<0)value=prefs.getInt("brightness",110);
          if(mode<0)mode=prefs.getInt("mode",Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
          Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,
            Math.max(1,Math.min(255,value)));
          Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,mode);
          prefs.edit().putBoolean("active",false).apply();
        }
      }catch(Exception e){android.util.Log.e("AmpyDesk","Brightness restoration failed",e);}
    }
    if(displayLock!=null && displayLock.isHeld()) {
      try{displayLock.release();}catch(Exception ignored){}
    }
    displayLock=null;active=false;
    originalBrightness=-1;originalMode=-1;context=null;
  }
}
