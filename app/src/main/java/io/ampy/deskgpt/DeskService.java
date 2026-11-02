package io.ampy.deskgpt;

import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class DeskService extends Service {
  static volatile boolean running=false;
  private volatile boolean serving=false;
  private ServerSocket server;
  private ExecutorService pool;
  private Thread listener;
  private PowerManager.WakeLock cpuLock;
  private WifiManager.WifiLock wifiLock;
  private static final int PORT=8777;
  private static final String CHANNEL="ampydesk_link";
  static String token(Context c) {
    var p=c.getSharedPreferences("desk",MODE_PRIVATE);
    String value=p.getString("token",null);
    if(value==null) {
      byte[] b=new byte[24]; new SecureRandom().nextBytes(b);
      value=android.util.Base64.encodeToString(b,android.util.Base64.URL_SAFE|android.util.Base64.NO_PADDING|android.util.Base64.NO_WRAP);
      p.edit().putString("token",value).apply();
    }
    return value;
  }
  static String ipAddress() {
    try {
      String fallback="127.0.0.1";
      Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
      while(en.hasMoreElements()) {
        NetworkInterface ni=en.nextElement();
        if(!ni.isUp() || ni.isLoopback()) continue;
        Enumeration<InetAddress> addresses=ni.getInetAddresses();
        while(addresses.hasMoreElements()) {
          InetAddress a=addresses.nextElement();
          if(a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
            if(ni.getName().startsWith("wlan") || ni.getName().startsWith("rndis") || ni.getName().startsWith("usb")) return a.getHostAddress();
            fallback=a.getHostAddress();
          }
        }
      }
      return fallback;
    } catch(Exception e) { return "127.0.0.1"; }
  }
  @Override public void onCreate() {
    super.onCreate();
    NotificationChannel channel=new NotificationChannel(CHANNEL,"AmpyDesk connection",NotificationManager.IMPORTANCE_LOW);
    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId) {
    Intent open=new Intent(this,MainActivity.class);
    PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notice=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle("AmpyDeskGPT • Server LAN").setContentText("Desktop attivo • tocca per gestire la connessione")
      .setOngoing(true).setContentIntent(pi).build();
    startForeground(8,notice);
    if(!serving) {
      serving=true;
      pool=Executors.newFixedThreadPool(8);
      try {
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        cpuLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AmpyDeskGPT:LAN"); cpuLock.acquire();
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"AmpyDeskGPT:WiFi");
        wifiLock.acquire();
      } catch(Exception ignored) {}
      listener=new Thread(this::loop,"AmpyDesk-LAN");
      listener.start();
    }
    return START_NOT_STICKY;
  }
  private void loop() {
    try {
      server=new ServerSocket();
      server.setReuseAddress(true);
      server.bind(new InetSocketAddress(PORT));
      running=true;
      while(serving) {
        Socket socket=server.accept();
        socket.setSoTimeout(25000);
        pool.submit(() -> { try(socket) { serve(socket); } catch(Exception ignored) {} });
      }
    } catch(Exception e) {
      android.util.Log.e("AmpyDesk","Server ended",e);
    } finally { running=false; serving=false; }
  }
  @Override public void onDestroy() {
    serving=false; running=false;
    try { if(server!=null) server.close(); } catch(Exception ignored) {}
    if(pool!=null) pool.shutdownNow();
    if(cpuLock!=null&&cpuLock.isHeld()) cpuLock.release();
    if(wifiLock!=null&&wifiLock.isHeld()) wifiLock.release();
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent i) { return null; }
  private static String line(InputStream in) throws IOException {
    ByteArrayOutputStream b=new ByteArrayOutputStream(); int c;
    while((c=in.read())!=-1) {
      if(c=='\n') break; if(c!='\r') b.write(c);
      if(b.size()>16384) throw new IOException("Header too long");
    }
    return b.toString("UTF-8");
  }
  private static String param(String query,String name) {
    if(query==null)return "";
    for(String p:query.split("&")) {
      String[] a=p.split("=",2);
      if(a.length>0 && a[0].equals(name))
        try { return URLDecoder.decode(a.length==2?a[1]:"","UTF-8"); } catch(Exception ignored) {}
    }
    return "";
  }
  private static String jsonError(String e) throws JSONException { return new JSONObject().put("error",e).toString(); }
  private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
  private static void respond(OutputStream out,int status,String type,byte[] data,Map<String,String> headers) throws IOException {
    String reason=status==200?"OK":status==204?"No Content":status==403?"Forbidden":status==404?"Not Found":status==413?"Payload Too Large":status==423?"Locked":"Error";
    StringBuilder h=new StringBuilder("HTTP/1.1 "+status+" "+reason+"\r\n"+
      "Content-Type: "+type+"\r\nContent-Length: "+data.length+"\r\n"+
      "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"+
      "Content-Security-Policy: default-src 'self' 'unsafe-inline' data: blob:; object-src 'none'; frame-ancestors 'none'\r\n"+
      "Connection: close\r\n");
    for(var e:headers.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
    out.write(bytes(h+"\r\n")); out.write(data); out.flush();
  }
  private static void send(OutputStream out,int status,String type,String content) throws IOException {
    respond(out,status,type,bytes(content),Collections.emptyMap());
  }
  private void serve(Socket socket) throws Exception {
    InputStream in=new BufferedInputStream(socket.getInputStream()); OutputStream out=socket.getOutputStream();
    String request=line(in); if(request.isEmpty())return;
    String[] parts=request.split(" ");
    if(parts.length<2)return;
    String method=parts[0], url=parts[1];
    int q=url.indexOf('?'); String path=q<0?url:url.substring(0,q); String query=q<0?"":url.substring(q+1);
    int size=0; int count=0;
    String header;
    while(!(header=line(in)).isEmpty()) {
      if(++count>100)throw new IOException("Too many headers");
      int i=header.indexOf(':');
      if(i>0 && header.substring(0,i).trim().equalsIgnoreCase("Content-Length"))
        size=Integer.parseInt(header.substring(i+1).trim());
    }
    if(!token(this).equals(param(query,"t"))) {
      send(out,403,"application/json",jsonError("Pairing token required. Open the link shown on the phone."));return;
    }
    if(size<0||size>100*1024*1024) {send(out,413,"application/json",jsonError("Maximum 100 MB"));return;}
    if(method.equals("GET") && path.equals("/")) {
      try(InputStream asset=getAssets().open("index.html")) {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buffer=new byte[8192];int n;
        while((n=asset.read(buffer))!=-1)b.write(buffer,0,n);
        respond(out,200,"text/html; charset=utf-8",b.toByteArray(),Collections.emptyMap());return;
      }
    }
    if(method.equals("GET") && path.equals("/bridge.exe")) {
      try(InputStream asset=getAssets().open("AmpyDeskBridge.exe")) {
        int length=asset.available();
        String header="HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+length+
          "\r\nContent-Disposition: attachment; filename=\"AmpyDeskBridge.exe\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(bytes(header));
        byte[] buffer=new byte[65536];int n;
        while((n=asset.read(buffer))!=-1)out.write(buffer,0,n);
        out.flush();return;
      } catch(FileNotFoundException ex) {
        send(out,404,"application/json",jsonError("Bridge not bundled in this build"));return;
      }
    }
    if(method.equals("GET") && path.equals("/api/status")) {
      DevicePolicyManager dpm=(DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);
      JSONObject obj=new JSONObject().put("server",running).put("network",ipAddress())
        .put("name",Build.MANUFACTURER+" "+Build.MODEL)
        .put("android",Build.VERSION.RELEASE).put("owner",dpm.isDeviceOwnerApp(getPackageName()))
        .put("accessibility",RemoteService.active!=null)
        .put("mode",getSharedPreferences("desk",MODE_PRIVATE).getString("mode","windowed"))
        .put("version","0.1.0").put("transferRoot","AmpyDeskGPT private Transfer folder");
      send(out,200,"application/json",obj.toString());return;
    }
    if(method.equals("GET") && path.equals("/api/apps")) {
      JSONArray result=new JSONArray();
      Intent intent=new Intent(Intent.ACTION_MAIN);intent.addCategory(Intent.CATEGORY_LAUNCHER);
      for(ResolveInfo info:getPackageManager().queryIntentActivities(intent,0)) {
        if(info.activityInfo==null)continue;
        result.put(new JSONObject().put("name",String.valueOf(info.loadLabel(getPackageManager())))
          .put("package",info.activityInfo.packageName));
      }
      send(out,200,"application/json",result.toString());return;
    }
    File root=new File(getExternalFilesDir(null),"Transfer"); root.mkdirs();
    if(method.equals("GET") && path.equals("/api/files")) {
      JSONArray arr=new JSONArray();File[] files=root.listFiles();
      if(files!=null)for(File file:files)if(file.isFile())
        arr.put(new JSONObject().put("name",file.getName()).put("size",file.length()));
      send(out,200,"application/json",arr.toString());return;
    }
    if(method.equals("POST") && path.equals("/api/mode")) {
      String mode=param(query,"mode");
      if(!mode.equals("windowed")&&!mode.equals("fullscreen")) {
        send(out,400,"application/json",jsonError("Invalid mode"));return;
      }
      getSharedPreferences("desk",MODE_PRIVATE).edit().putString("mode",mode).apply();
      send(out,200,"application/json","{\"ok\":true}");return;
    }
    if(path.equals("/api/upload")||path.equals("/api/download")) {
      String name=param(query,"name");
      if(name.isEmpty()||name.length()>180||name.contains("/")||name.contains("\\")||name.equals(".")||name.equals("..")) {
        send(out,400,"application/json",jsonError("Invalid filename"));return;
      }
      File file=new File(root,name);
      if(method.equals("POST")&&path.equals("/api/upload")) {
        File temp=new File(root,name+".uploading");
        boolean copied=false;
        try(FileOutputStream dest=new FileOutputStream(temp)) {
          byte[] buffer=new byte[32768];int left=size;int n;
          while(left>0&&(n=in.read(buffer,0,Math.min(buffer.length,left)))!=-1) { dest.write(buffer,0,n);left-=n; }
          if(left!=0)throw new IOException("Incomplete upload");
          copied=true;
        } finally {if(!copied)temp.delete();}
        if(file.exists()&&!file.delete())throw new IOException("Cannot replace file");
        if(!temp.renameTo(file))throw new IOException("Cannot finalize upload");
        send(out,200,"application/json","{\"ok\":true}");return;
      }
      if(method.equals("GET")&&path.equals("/api/download")&&file.isFile()) {
        String h="HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+file.length()+
          "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"+
          "Content-Disposition: attachment; filename=\""+name.replace("\"","")+"\"\r\nConnection: close\r\n\r\n";
        out.write(bytes(h));try(FileInputStream src=new FileInputStream(file)) {
          byte[] buffer=new byte[32768];int n;while((n=src.read(buffer))!=-1)out.write(buffer,0,n);
        }out.flush();return;
      }
    }
    if(method.equals("POST") && path.equals("/api/launch")) {
      String pkg=param(query,"package");
      Intent launch=getPackageManager().getLaunchIntentForPackage(pkg);
      if(launch==null){send(out,404,"application/json",jsonError("App not launchable"));return;}
      try {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        send(out,200,"application/json","{\"requested\":true}");return;
      } catch(Exception e) {send(out,409,"application/json",jsonError("Android blocked background launch: "+e.getMessage()));return;}
    }
    RemoteService remote=RemoteService.active;
    boolean screenOn=((PowerManager)getSystemService(POWER_SERVICE)).isInteractive();
    if(method.equals("GET") && path.equals("/api/screen")) {
      if(remote==null||!screenOn){send(out,423,"application/json",jsonError("Enable Accessibility and unlock the phone"));return;}
      byte[] image=remote.lastScreenshot();
      if(image!=null)respond(out,200,"image/jpeg",image,Collections.emptyMap());
      else send(out,409,"application/json",jsonError("Screenshot not ready"));
      return;
    }
    if(method.equals("POST") && path.equals("/api/tap")) {
      if(remote==null||!screenOn){send(out,423,"application/json",jsonError("Screen locked or accessibility unavailable"));return;}
      float x=Float.parseFloat(param(query,"x")),y=Float.parseFloat(param(query,"y"));
      if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>1||y<0||y>1) {
        send(out,400,"application/json",jsonError("Coordinates must be 0..1"));return;
      }
      send(out,200,"application/json",new JSONObject().put("sent",remote.tap(x,y)).toString());return;
    }
    if(method.equals("POST") && path.equals("/api/action")) {
      if(remote==null||!screenOn){send(out,423,"application/json",jsonError("Screen locked or accessibility unavailable"));return;}
      send(out,200,"application/json",new JSONObject().put("sent",remote.action(param(query,"name"))).toString());return;
    }
    send(out,404,"application/json",jsonError("Not found"));
  }
}
