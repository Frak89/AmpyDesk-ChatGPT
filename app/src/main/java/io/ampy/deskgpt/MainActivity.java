package io.ampy.deskgpt;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.media.projection.MediaProjectionManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
  private LinearLayout root;
  private TextView status;
  private static final int REQUEST_CAPTURE=150;
  private void requestLiveCapture() {
    MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
    try {startActivityForResult(manager.createScreenCaptureIntent(),REQUEST_CAPTURE);}
    catch(Exception e) {Toast.makeText(this,"Cattura non disponibile: "+e.getMessage(),Toast.LENGTH_LONG).show();}
  }
  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==REQUEST_CAPTURE) {
      if(resultCode==RESULT_OK && data!=null) {
        Intent capture=new Intent(this,ProjectionService.class);
        capture.putExtra("resultCode",resultCode).putExtra("resultData",data);
        try {startForegroundService(capture);Toast.makeText(this,"Live sharing requested",Toast.LENGTH_SHORT).show();}
        catch(Exception e) {Toast.makeText(this,"Projection error: "+e.getMessage(),Toast.LENGTH_LONG).show();}
      }
      else Toast.makeText(this,"Cattura annullata",Toast.LENGTH_SHORT).show();
      refresh();
    }
  }
  private void line(String s, int size) {
    TextView text = new TextView(this);
    text.setText(s); text.setTextSize(size); text.setTextColor(Color.rgb(193,231,238));
    text.setPadding(6,15,6,15); root.addView(text);
  }
  private void button(String label, Runnable task) {
    Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> task.run()); root.addView(b);
  }
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().setStatusBarColor(Color.rgb(5,12,22));
    ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
    root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(26,32,26,32);
    root.setBackgroundColor(Color.rgb(5,12,22)); scroll.addView(root); setContentView(scroll);
    line("◈ AMPY DESK GPT",27);
    line("AMPYBIOS • LIVE DESKTOP BRIDGE 0.2",13);
    status = new TextView(this); status.setTextColor(Color.rgb(100,240,226)); status.setTextSize(16);
    status.setPadding(8,25,8,25); root.addView(status);
    button("▶ AVVIA DESKTOP / SERVER", () -> {
      if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},50);
      try { startForegroundService(new Intent(this, DeskService.class)); Toast.makeText(this,"Server avviato",Toast.LENGTH_SHORT).show(); }
      catch (Exception e) { Toast.makeText(this,"Errore: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
      refresh();
    });
    button("◉ AVVIA VIDEO LIVE APPLICAZIONI (consenso Android)", this::requestLiveCapture);
    button("□ INTERROMPI VIDEO LIVE", () -> { stopService(new Intent(this,ProjectionService.class)); refresh(); });
    button("■ FERMA DESKTOP", () -> { stopService(new Intent(this, DeskService.class)); refresh(); });
    button("▢ WINDOWS BRIDGE • WINDOWED", () -> { getSharedPreferences("desk",MODE_PRIVATE).edit().putString("mode","windowed").apply(); refresh(); });
    button("⛶ WINDOWS BRIDGE • FULLSCREEN", () -> { getSharedPreferences("desk",MODE_PRIVATE).edit().putString("mode","fullscreen").apply(); refresh(); });
    button("♧ ABILITA ACCESSIBILITÀ / CONTROLLO TOUCH", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    button("◉ IMPOSTAZIONI DEVICE OWNER", () -> {
      DevicePolicyManager dpm = (DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);
      if(dpm.isDeviceOwnerApp(getPackageName())) Toast.makeText(this,"Device Owner ATTIVO",Toast.LENGTH_LONG).show();
      else Toast.makeText(this,"Richiede provisioning ADB su dispositivo idoneo. Leggi README.",Toast.LENGTH_LONG).show();
      refresh();
    });
    button("⟳ AGGIORNA STATO / URL", this::refresh);
    line("Connetti il PC alla stessa rete Wi-Fi o usa tethering USB. Copia il link completo visualizzato sopra nel browser del PC.",14);
    line("Live Video: seleziona l'intero schermo nella finestra di consenso di Android. Il video si interrompe quando blocchi il telefono. Per mantenere il display fisico spento servono autorizzazioni ADB separate; Device Owner non basta.",13);
    line("La finestra Remote Phone sul PC mostra un singolo display Android, non piu' finestre di app indipendenti. Il keymapper invia tocchi brevi con Accessibilita'.",13);
    refresh();
  }
  private void refresh() {
    DevicePolicyManager dpm=(DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);
    String ip = DeskService.ipAddress();
    String token = DeskService.token(this);
    status.setText("SERVER: "+(DeskService.running?"ATTIVO":"FERMO")+
      "\nLAN: http://"+ip+":8777/?t="+token+
      "\nBridge mode: "+getSharedPreferences("desk",MODE_PRIVATE).getString("mode","windowed")+
      "\nDevice Owner: "+(dpm.isDeviceOwnerApp(getPackageName())?"ATTIVO":"NON ATTIVO")+
      "\nAccessibilità: "+(RemoteService.active!=null?"ATTIVA":"NON ATTIVA")+
      "\nVideo Live: "+(ProjectionService.active?"ATTIVO":"NON ATTIVO"));
  }
  @Override protected void onResume() { super.onResume(); if(status!=null) refresh(); }
}
