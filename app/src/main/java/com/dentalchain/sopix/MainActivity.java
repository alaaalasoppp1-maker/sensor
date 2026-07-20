package com.dentalchain.sopix;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity implements SopixUsb.Listener {
    private SopixUsb usb;
    private TextView status, log;
    private EditText patient, command;
    private RawImageView image;
    private File lastRaw;
    private final Handler ui=new Handler(Looper.getMainLooper());

    private final BroadcastReceiver receiver=new BroadcastReceiver(){ public void onReceive(Context c,Intent i){
        if(SopixUsb.ACTION_USB_PERMISSION.equals(i.getAction())) { UsbDevice d=i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE); boolean ok=i.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED,false); if(ok&&d!=null)usb.open(d); else error("تم رفض صلاحية USB"); }
    }};

    @Override public void onCreate(Bundle b){ super.onCreate(b); buildUi(); usb=new SopixUsb(this,this); registerReceiver(receiver,new IntentFilter(SopixUsb.ACTION_USB_PERMISSION),Context.RECEIVER_NOT_EXPORTED); }
    @Override protected void onDestroy(){ super.onDestroy(); unregisterReceiver(receiver); usb.close(); }

    private TextView tv(String s,int sp){ TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.WHITE);v.setPadding(14,10,14,10);return v; }
    private Button btn(String s,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setOnClickListener(l);return b;}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(12,12,12,12);root.setBackgroundColor(Color.rgb(8,19,31));
        TextView title=tv("SOPIX T1 • USB DIAGNOSTIC",20);title.setGravity(Gravity.CENTER);root.addView(title);
        patient=new EditText(this);patient.setHint("اسم المريض");patient.setTextColor(Color.WHITE);patient.setHintTextColor(Color.LTGRAY);root.addView(patient,new LinearLayout.LayoutParams(-1,-2));
        status=tv("غير متصل",16);status.setGravity(Gravity.CENTER);root.addView(status);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(btn("اتصال",v->usb.requestOrOpen()),new LinearLayout.LayoutParams(0,-2,1));
        row.addView(btn("التقاط RAW",v->capture()),new LinearLayout.LayoutParams(0,-2,1));
        row.addView(btn("إيقاف",v->usb.stopCapture()),new LinearLayout.LayoutParams(0,-2,1));root.addView(row);
        LinearLayout cmdRow=new LinearLayout(this);command=new EditText(this);command.setHint("أمر HEX إلى 0x06");command.setTextColor(Color.WHITE);command.setHintTextColor(Color.LTGRAY);cmdRow.addView(command,new LinearLayout.LayoutParams(0,-2,1));cmdRow.addView(btn("إرسال",v->usb.sendHex(command.getText().toString())));root.addView(cmdRow);
        image=new RawImageView(this);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout previewRow=new LinearLayout(this);previewRow.addView(btn("عرض RAW 1250×1050",v->preview()),new LinearLayout.LayoutParams(0,-2,1));previewRow.addView(btn("مسح السجل",v->log.setText("")),new LinearLayout.LayoutParams(0,-2,1));root.addView(previewRow);
        log=tv("جاهز. صِل الحساس ثم اضغط اتصال.\n",12);log.setTextIsSelectable(true);ScrollView sv=new ScrollView(this);sv.addView(log);root.addView(sv,new LinearLayout.LayoutParams(-1,260));
        setContentView(root);
    }
    private void capture(){String p=patient.getText().toString().trim();if(p.isEmpty())p="Patient";p=p.replaceAll("[^\\p{L}\\p{N}._-]+","_");File dir=new File(getExternalFilesDir(null),"SopixRaw");dir.mkdirs();String t=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());lastRaw=new File(dir,p+"_"+t+".raw");usb.captureRaw(lastRaw,2625000,1500);}
    private void preview(){if(lastRaw==null||!lastRaw.exists()){error("لا يوجد ملف RAW بعد");return;}new Thread(()->{try{image.loadRaw16(lastRaw,1250,1050,1.0f,0f);log("تم إنشاء معاينة RAW");}catch(Exception e){error("Preview: "+e.getMessage());}}).start();}
    public void log(String s){ui.post(()->{log.append(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s+"\n");});}
    public void connected(boolean v){ui.post(()->{status.setText(v?"الحساس متصل":"غير متصل");status.setTextColor(v?Color.GREEN:Color.LTGRAY);});}
    public void rawSaved(File f,int bytes){ui.post(()->{lastRaw=f;log("تم حفظ: "+f.getAbsolutePath());preview();});}
    public void error(String s){ui.post(()->{log("خطأ: "+s);Toast.makeText(this,s,Toast.LENGTH_LONG).show();});}
}
