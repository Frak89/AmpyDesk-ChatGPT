package io.ampy.deskgpt;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** User-consented, foreground-only MediaProjection frame source.
 * Frames are JPEG previews, not a guaranteed high-FPS video codec.
 * On Android 15+ locking the screen stops MediaProjection.
 */
public final class ProjectionService extends Service {
  public static final String ACTION_STOP="io.ampy.deskgpt.STOP_PROJECTION";
  public static volatile boolean active=false;
  private static final Object LOCK=new Object();
  private static byte[] lastFrame=null;
  private static long sequence=0;
  private static volatile long lastFrameTimestamp=0;
  private MediaProjection projection;
  private VirtualDisplay virtualDisplay;
  private ImageReader reader;
  private HandlerThread worker;
  private Handler workerHandler;
  private boolean stopping=false;
  private static final String CHANNEL="ampydesk_live";

  public static final class Frame {
    public final byte[] data;
    public final long serial;
    Frame(byte[] bytes,long serial){this.data=bytes;this.serial=serial;}
  }
  public static Frame getFrame(long after,long timeoutMs) {
    synchronized(LOCK) {
      if(sequence<=after && active) {
        try{ LOCK.wait(Math.max(1,Math.min(1500,timeoutMs))); }
        catch(InterruptedException e){Thread.currentThread().interrupt();}
      }
      return lastFrame!=null && sequence>after?new Frame(lastFrame,sequence):null;
    }
  }
  @Override public void onCreate() {
    super.onCreate();
    NotificationChannel channel=new NotificationChannel(CHANNEL,"AmpyDesk live display",NotificationManager.IMPORTANCE_LOW);
    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId) {
    if(intent==null) {stopSelf();return START_NOT_STICKY;}
    if(ACTION_STOP.equals(intent.getAction())) {stopSelf();return START_NOT_STICKY;}
    final int resultCode=intent.getIntExtra("resultCode",Activity.RESULT_CANCELED);
    final Intent result=intent.getParcelableExtra("resultData");
    if(resultCode!=Activity.RESULT_OK||result==null) {stopSelf();return START_NOT_STICKY;}
    PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    Intent end=new Intent(this,ProjectionService.class).setAction(ACTION_STOP);
    PendingIntent stop=PendingIntent.getService(this,2,end,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    Notification n=new Notification.Builder(this,CHANNEL)
      .setSmallIcon(android.R.drawable.presence_video_online)
      .setContentTitle("AmpyDeskGPT • LIVE Android display")
      .setContentText("Screen sharing active. Tap Stop to disconnect.")
      .setContentIntent(open).addAction(android.R.drawable.ic_media_pause,"Stop",stop).setOngoing(true).build();
    startForeground(13,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    try {
      MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
      projection=manager.getMediaProjection(resultCode,result);
      if(projection==null) throw new IllegalStateException("Projection permission unavailable");
      worker=new HandlerThread("AmpyDesk-frames");worker.start();
      workerHandler=new Handler(worker.getLooper());
      projection.registerCallback(new MediaProjection.Callback(){
        @Override public void onStop(){stopSelf();}
      },workerHandler);
      Rect bounds=((WindowManager)getSystemService(WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();
      int origWidth=Math.max(1,bounds.width()),origHeight=Math.max(1,bounds.height());
      int width=Math.max(2,Math.round(900f*Math.min(1f,origWidth/900f)));
      width=width+(width%2);
      int height=Math.max(2,Math.round(origHeight*(width/(float)origWidth)));
      height=height+(height%2);
      reader=ImageReader.newInstance(width,height,android.graphics.PixelFormat.RGBA_8888,3);
      reader.setOnImageAvailableListener(this::handleImage,workerHandler);
      active=true;
      virtualDisplay=projection.createVirtualDisplay("AmpyDeskLive",width,height,
         getResources().getConfiguration().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
         reader.getSurface(),null,workerHandler);
    }catch(Exception e){android.util.Log.e("AmpyDesk","MediaProjection failed",e);stopSelf();}
    return START_NOT_STICKY;
  }
  private void handleImage(ImageReader imageReader) {
    Image image=null;
    try {
      image=imageReader.acquireLatestImage(); if(image==null)return;
      long now=android.os.SystemClock.elapsedRealtime();
      if(now-lastFrameTimestamp<70)return;
      lastFrameTimestamp=now;
      int w=image.getWidth(),h=image.getHeight();
      Image.Plane plane=image.getPlanes()[0];
      ByteBuffer b=plane.getBuffer();
      int stride=plane.getRowStride(),pixelStride=plane.getPixelStride();
      if(pixelStride!=4 || stride%4!=0)return;
      Bitmap raw=Bitmap.createBitmap(stride/4,h,Bitmap.Config.ARGB_8888);
      try {
        b.rewind();raw.copyPixelsFromBuffer(b);
        Bitmap visible=Bitmap.createBitmap(raw,0,0,w,h);
        try {
          ByteArrayOutputStream bytes=new ByteArrayOutputStream(100000);
          if(visible.compress(Bitmap.CompressFormat.JPEG,70,bytes)) {
            synchronized(LOCK) {
              lastFrame=bytes.toByteArray(); sequence++; LOCK.notifyAll();
            }
          }
        }finally {visible.recycle();}
      }finally {raw.recycle();}
    }catch(Exception e){android.util.Log.w("AmpyDesk","frame failed",e);}
    finally {if(image!=null)image.close();}
  }
  @Override public void onDestroy() {
    if(!stopping){
      stopping=true;active=false;
      synchronized(LOCK){lastFrame=null;sequence++;LOCK.notifyAll();}
      if(reader!=null){reader.setOnImageAvailableListener(null,null);}
      if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
      if(reader!=null){reader.close();reader=null;}
      if(projection!=null){MediaProjection p=projection;projection=null;p.stop();}
      if(worker!=null){worker.quitSafely();worker=null;}
    }
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent intent){return null;}
}
