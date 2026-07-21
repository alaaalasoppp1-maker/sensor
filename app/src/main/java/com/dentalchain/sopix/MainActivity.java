package com.dentalchain.sopix;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.view.*;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity implements SopixUsb.Listener {
    private SopixUsb usb;
    private TextView statusTitle, statusSub, log;
    private EditText patient, command;
    private RawImageView image;
    private ProgressBar progress;
    private Button mainButton, connectButton, advancedButton;
    private LinearLayout advancedPanel;
    private File lastRaw;
    private boolean connected;
    private final Handler ui=new Handler(Looper.getMainLooper());

    private final BroadcastReceiver receiver=new BroadcastReceiver(){ public void onReceive(Context c,Intent i){
        if(SopixUsb.ACTION_USB_PERMISSION.equals(i.getAction())) { UsbDevice d=i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE); boolean ok=i.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED,false); if(ok&&d!=null)usb.open(d); else error("تم رفض صلاحية USB"); }
    }};

    @Override public void onCreate(Bundle b){ super.onCreate(b); buildUi(); usb=new SopixUsb(this,this); registerReceiver(receiver,new IntentFilter(SopixUsb.ACTION_USB_PERMISSION),Context.RECEIVER_NOT_EXPORTED); }
    @Override protected void onResume(){ super.onResume(); if(usb!=null && !usb.isOpen()) usb.requestOrOpen(); }
    @Override protected void onDestroy(){ super.onDestroy(); unregisterReceiver(receiver); usb.close(); }

    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+.5f); }
    private GradientDrawable bg(int color,int radius){ GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d; }
    private TextView text(String s,int sp,int color){ TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);return v; }
    private Button button(String s,int color){ Button b=new Button(this);b.setText(s);b.setTextSize(16);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackground(bg(color,16));b.setPadding(dp(16),0,dp(16),0);return b; }
    private LinearLayout.LayoutParams lp(int w,int h){ return new LinearLayout.LayoutParams(w,h); }

    private void buildUi(){
        getWindow().setStatusBarColor(Color.rgb(7,18,30));
        getWindow().setNavigationBarColor(Color.rgb(7,18,30));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(16),dp(18),dp(12));root.setBackgroundColor(Color.rgb(7,18,30));

        TextView title=text("DENTAL CHAIN SENSOR",23,Color.WHITE);title.setTypeface(null,1);title.setGravity(Gravity.CENTER);root.addView(title,lp(-1,dp(42)));
        TextView subtitle=text("SOPIX T1  •  DIGITAL X-RAY",12,Color.rgb(145,164,184));subtitle.setGravity(Gravity.CENTER);root.addView(subtitle,lp(-1,dp(26)));

        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(15),dp(18),dp(15));card.setBackground(bg(Color.rgb(15,31,48),20));
        statusTitle=text("جاري فحص الحساس…",18,Color.WHITE);statusTitle.setGravity(Gravity.RIGHT);statusTitle.setTypeface(null,1);card.addView(statusTitle);
        statusSub=text("صِل الحساس عبر USB OTG",13,Color.rgb(155,174,194));statusSub.setGravity(Gravity.RIGHT);card.addView(statusSub);
        LinearLayout.LayoutParams cardLp=lp(-1,-2);cardLp.setMargins(0,dp(10),0,dp(14));root.addView(card,cardLp);

        patient=new EditText(this);patient.setHint("اسم المريض أو رقم الملف");patient.setTextColor(Color.WHITE);patient.setHintTextColor(Color.rgb(130,149,168));patient.setTextSize(17);patient.setSingleLine(true);patient.setGravity(Gravity.RIGHT);patient.setPadding(dp(16),0,dp(16),0);patient.setBackground(bg(Color.rgb(15,31,48),16));root.addView(patient,lp(-1,dp(58)));

        mainButton=button("بدء جلسة التصوير",Color.rgb(18,151,121));mainButton.setEnabled(false);mainButton.setOnClickListener(v->capture());LinearLayout.LayoutParams mainLp=lp(-1,dp(62));mainLp.setMargins(0,dp(14),0,dp(10));root.addView(mainButton,mainLp);

        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setWeightSum(2);
        connectButton=button("فحص الاتصال",Color.rgb(42,75,108));connectButton.setOnClickListener(v->usb.requestOrOpen());row.addView(connectButton,new LinearLayout.LayoutParams(0,dp(50),1));
        Button stop=button("إيقاف",Color.rgb(104,54,64));stop.setOnClickListener(v->usb.stopCapture());LinearLayout.LayoutParams stopLp=new LinearLayout.LayoutParams(0,dp(50),1);stopLp.setMargins(dp(10),0,0,0);row.addView(stop,stopLp);root.addView(row);

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(0);LinearLayout.LayoutParams pLp=lp(-1,dp(8));pLp.setMargins(0,dp(12),0,dp(10));root.addView(progress,pLp);

        image=new RawImageView(this);image.setBackground(bg(Color.BLACK,18));root.addView(image,new LinearLayout.LayoutParams(-1,0,1));

        advancedButton=button("السجل والأدوات المتقدمة",Color.rgb(30,51,71));advancedButton.setTextSize(13);advancedButton.setOnClickListener(v->{advancedPanel.setVisibility(advancedPanel.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});LinearLayout.LayoutParams advLp=lp(-1,dp(44));advLp.setMargins(0,dp(10),0,0);root.addView(advancedButton,advLp);

        advancedPanel=new LinearLayout(this);advancedPanel.setOrientation(LinearLayout.VERTICAL);advancedPanel.setVisibility(View.GONE);advancedPanel.setPadding(0,dp(8),0,0);
        LinearLayout cmdRow=new LinearLayout(this);command=new EditText(this);command.setHint("HEX إلى EP 0x06");command.setTextColor(Color.WHITE);command.setHintTextColor(Color.GRAY);command.setSingleLine(true);command.setBackground(bg(Color.rgb(15,31,48),12));cmdRow.addView(command,new LinearLayout.LayoutParams(0,dp(48),1));Button send=button("إرسال",Color.rgb(42,75,108));send.setOnClickListener(v->usb.sendHex(command.getText().toString()));LinearLayout.LayoutParams sendLp=lp(dp(92),dp(48));sendLp.setMargins(dp(8),0,0,0);cmdRow.addView(send,sendLp);advancedPanel.addView(cmdRow);
        log=text("جاهز\n",11,Color.rgb(190,205,220));log.setTextIsSelectable(true);log.setPadding(dp(10),dp(8),dp(10),dp(8));ScrollView sv=new ScrollView(this);sv.addView(log);advancedPanel.addView(sv,lp(-1,dp(150)));root.addView(advancedPanel);
        setContentView(root);
    }

    private void capture(){
        String p=patient.getText().toString().trim();if(p.isEmpty()){patient.setError("اكتب اسم المريض");patient.requestFocus();return;}
        p=p.replaceAll("[^\\p{L}\\p{N}._-]+","_");File dir=new File(getExternalFilesDir(null),"SopixRaw");dir.mkdirs();String t=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());lastRaw=new File(dir,p+"_"+t+".raw");usb.initializeAndCapture(lastRaw,2625000);
    }

    private void preview(){if(lastRaw==null||!lastRaw.exists())return;new Thread(()->{try{image.loadRaw16(lastRaw,1250,1050,1.0f,0f);log("تم إنشاء معاينة أولية");}catch(Exception e){error("Preview: "+e.getMessage());}}).start();}
    public void log(String s){ui.post(()->log.append(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s+"\n"));}
    public void connected(boolean v){ui.post(()->{connected=v;mainButton.setEnabled(v);statusTitle.setText(v?"الحساس متصل وجاهز":"الحساس غير متصل");statusTitle.setTextColor(v?Color.rgb(80,225,176):Color.WHITE);statusSub.setText(v?"اكتب اسم المريض ثم ابدأ جلسة التصوير":"صِل الحساس عبر USB OTG ثم اضغط فحص الاتصال");});}
    public void progress(int value,String label){ui.post(()->{progress.setProgress(value);statusSub.setText(label);});}
    public void rawSaved(File f,int bytes){ui.post(()->{lastRaw=f;statusTitle.setText("تم استلام الصورة");statusSub.setText(String.format(Locale.US,"%,d bytes",bytes));log("تم الحفظ: "+f.getAbsolutePath());preview();});}
    public void error(String s){ui.post(()->{statusTitle.setText("تعذر إكمال العملية");statusTitle.setTextColor(Color.rgb(255,125,125));statusSub.setText(s);progress.setProgress(0);log("خطأ: "+s);Toast.makeText(this,s,Toast.LENGTH_LONG).show();});}
}
