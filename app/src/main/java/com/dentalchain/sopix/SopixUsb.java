package com.dentalchain.sopix;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SopixUsb {
    public static final int VID=0x1CE6, PID=0x0001;
    public static final String ACTION_USB_PERMISSION="com.dentalchain.sopix.USB_PERMISSION";

    public interface Listener {
        void log(String text); void connected(boolean value); void progress(int percent,String label);
        void rawSaved(File file,int bytes); void error(String text);
    }

    private final Context context; private final UsbManager manager; private final Listener listener;
    private UsbDevice device; private UsbDeviceConnection connection; private UsbInterface intf;
    private UsbEndpoint epOut06,epIn81,epIn82,epIn88;
    private final AtomicBoolean running=new AtomicBoolean(false);

    public SopixUsb(Context c,Listener l){context=c;listener=l;manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);}
    public UsbDevice find(){for(UsbDevice d:manager.getDeviceList().values())if(d.getVendorId()==VID&&d.getProductId()==PID)return d;return null;}
    public boolean isOpen(){return connection!=null&&epOut06!=null&&epIn82!=null&&epIn88!=null;}

    public void requestOrOpen(){
        UsbDevice d=find(); if(d==null){listener.error("لم يتم العثور على الحساس");return;}
        device=d;
        if(!manager.hasPermission(d)){
            PendingIntent pi=PendingIntent.getBroadcast(context,0,new Intent(ACTION_USB_PERMISSION),PendingIntent.FLAG_MUTABLE);
            manager.requestPermission(d,pi); listener.log("بانتظار صلاحية USB");
        }else open(d);
    }

    public synchronized void open(UsbDevice d){
        closeConnectionOnly(); device=d; connection=manager.openDevice(d);
        if(connection==null){listener.error("تعذر فتح اتصال USB");return;}
        for(int i=0;i<d.getInterfaceCount();i++){
            UsbInterface f=d.getInterface(i); if(!connection.claimInterface(f,true))continue;
            UsbEndpoint o=null,i81=null,i82=null,i88=null;
            for(int e=0;e<f.getEndpointCount();e++){
                UsbEndpoint p=f.getEndpoint(e); int a=p.getAddress()&255;
                if(a==0x06)o=p; else if(a==0x81)i81=p; else if(a==0x82)i82=p; else if(a==0x88)i88=p;
            }
            if(o!=null&&i82!=null&&i88!=null){intf=f;epOut06=o;epIn81=i81;epIn82=i82;epIn88=i88;break;}
            connection.releaseInterface(f);
        }
        if(!isOpen()){closeConnectionOnly();listener.error("واجهة الحساس غير مكتملة");return;}
        listener.log("SOPIX T1 متصل — 1CE6:0001"); listener.connected(true);
    }

    private synchronized boolean reopen(){
        UsbDevice d=device!=null?device:find(); if(d==null||!manager.hasPermission(d))return false;
        closeConnectionOnly(); try{Thread.sleep(250);}catch(InterruptedException ignored){} open(d); return isOpen();
    }

    private int transfer(UsbEndpoint ep,byte[] b,int timeout){return connection==null?-1:connection.bulkTransfer(ep,b,b.length,timeout);}

    private void drainEp88(){
        if(connection==null||epIn88==null)return;
        byte[] stale=new byte[2048];
        for(int i=0;i<8;i++){
            int n=connection.bulkTransfer(epIn88,stale,stale.length,20);
            if(n<=0)break;
            listener.log("تجاهل رد قديم من EP88: "+hex(stale,Math.min(n,24)));
        }
    }

    private boolean sendAndAck(SopixProtocol.Step step)throws IOException{
        if(step.delayMs>0)try{Thread.sleep(step.delayMs);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}

        // EP88 may still contain a short reply from the previous Windows-style transaction.
        // Drain it before sending so a stale 2-byte packet is not misclassified as rejection.
        drainEp88();

        int sent=transfer(epOut06,step.data,1800);
        if(sent!=step.data.length)throw new IOException("فشل إرسال أمر التهيئة "+step.sequence()+" (USB="+sent+")");

        byte[] r=new byte[2048];
        long until=System.currentTimeMillis()+(step.ackRequired?3200:900);
        while(running.get()&&System.currentTimeMillis()<until){
            int n=connection.bulkTransfer(epIn88,r,r.length,220);
            if(n>0){
                listener.log("EP88 ["+n+"]: "+hex(r,Math.min(n,32)));

                // In the trace, the second byte is not a documented success/error code.
                // A matching sequence byte confirms that the sensor consumed the command.
                if((r[0]&255)==step.sequence()){
                    listener.log("✓ أمر "+step.sequence()+" مؤكد");
                    return true;
                }

                // Some responses prepend a status byte; accept the sequence in byte 2 as well.
                if(n>=2&&(r[1]&255)==step.sequence()){
                    listener.log("✓ أمر "+step.sequence()+" مؤكد");
                    return true;
                }
            }
        }
        if(!step.ackRequired){
            listener.log("✓ تم إرسال أمر "+step.sequence()+" — متابعة انتظار الصورة");
            return true;
        }
        throw new IOException("لم يصل تأكيد أمر التهيئة "+step.sequence());
    }

    public void initializeAndCapture(File output,int expectedBytes){
        if(!isOpen()){listener.error("الحساس غير متصل");return;}
        if(!running.compareAndSet(false,true)){listener.error("هناك جلسة تصوير جارية");return;}
        new Thread(()->runCapture(output,expectedBytes),"sopix-capture").start();
    }

    private void runCapture(File output,int expectedBytes){
        int total=0;
        try{
            listener.progress(2,"تجهيز اتصال الحساس");
            if(!reopen())throw new IOException("تعذر إعادة فتح الحساس");

            // Do not poll EP81 while command acknowledgements are being read from EP88.
            // Concurrent bulkTransfer calls on several Android USB stacks caused command 10 to be lost.
            listener.progress(8,"تهيئة الحساس");
            for(int i=0;i<SopixProtocol.ACQUIRE.length&&running.get();i++){
                sendAndAck(SopixProtocol.ACQUIRE[i]);
                listener.progress(8+(i+1)*27/SopixProtocol.ACQUIRE.length,"تهيئة الحساس "+(i+1)+"/"+SopixProtocol.ACQUIRE.length);
            }

            listener.progress(38,"الحساس جاهز — قم بالتعريض الآن");
            listener.log("جاهز لاستقبال 1250×1050 من EP82");
            byte[] imageBuffer=new byte[Math.max(65536,epIn82.getMaxPacketSize()*256)];
            long deadline=System.currentTimeMillis()+90000; int idle=0; boolean started=false;
            try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
                while(running.get()&&total<expectedBytes&&System.currentTimeMillis()<deadline){
                    int n=connection.bulkTransfer(epIn82,imageBuffer,imageBuffer.length,350);
                    if(n>0){
                        if(!started){started=true;listener.log("بدأ وصول الصورة");}
                        idle=0; out.write(imageBuffer,0,n); total+=n;
                        listener.progress(40+Math.min(58,(int)(total*58L/expectedBytes)),"استقبال الصورة");
                    }else if(started){idle+=350;if(idle>=2100)break;}
                }
                out.flush();
            }
            if(!started||total==0)throw new IOException("لم تصل بيانات الصورة بعد التعريض");
            if(total<expectedBytes)listener.log("تنبيه: حجم الصورة "+total+" بدل "+expectedBytes);
            listener.progress(100,"اكتملت الصورة"); listener.rawSaved(output,total);
        }catch(Exception e){
            if(output.exists()&&output.length()==0)output.delete();
            listener.error(e.getMessage()==null?"فشل الالتقاط":e.getMessage());
        }finally{running.set(false);}
    }

    public void sendHex(String h){
        if(!isOpen()){listener.error("الحساس غير متصل");return;}String c=h.replaceAll("[^0-9A-Fa-f]","");
        if(c.length()==0||(c.length()&1)!=0){listener.error("قيمة HEX غير صالحة");return;}
        byte[] b=new byte[c.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(c.substring(i*2,i*2+2),16);
        int n=transfer(epOut06,b,1500);listener.log("HEX OUT: "+n+" bytes");
    }
    private static String hex(byte[] b,int n){StringBuilder s=new StringBuilder();for(int i=0;i<n;i++)s.append(String.format(Locale.US,"%02X",b[i]&255)).append(i+1<n?' ':' ');return s.toString().trim();}
    public void stopCapture(){running.set(false);listener.progress(0,"تم إيقاف الجلسة");}
    private synchronized void closeConnectionOnly(){if(connection!=null&&intf!=null)try{connection.releaseInterface(intf);}catch(Exception ignored){}if(connection!=null)try{connection.close();}catch(Exception ignored){}connection=null;intf=null;epOut06=null;epIn81=null;epIn82=null;epIn88=null;}
    public void close(){running.set(false);closeConnectionOnly();listener.connected(false);}
}
