package com.dentalchain.sopix;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SopixUsb {
    public static final int VID = 0x1CE6;
    public static final int PID = 0x0001;
    public static final String ACTION_USB_PERMISSION = "com.dentalchain.sopix.USB_PERMISSION";

    public interface Listener {
        void log(String text);
        void connected(boolean value);
        void progress(int percent, String label);
        void rawSaved(File file, int bytes);
        void error(String text);
    }

    private final Context context;
    private final UsbManager manager;
    private final Listener listener;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface intf;
    private UsbEndpoint epOut06, epIn81, epIn82, epIn88;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SopixUsb(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public UsbDevice find() {
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    public void requestOrOpen() {
        device = find();
        if (device == null) { listener.error("لم يتم العثور على حساس SOPIX T1"); return; }
        if (!manager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
            manager.requestPermission(device, pi);
            listener.log("تم طلب صلاحية USB");
        } else open(device);
    }

    public void open(UsbDevice d) {
        close();
        device = d;
        connection = manager.openDevice(d);
        if (connection == null) { listener.error("تعذر فتح اتصال USB"); return; }
        for (int i=0;i<d.getInterfaceCount();i++) {
            UsbInterface candidate = d.getInterface(i);
            if (!connection.claimInterface(candidate, true)) continue;
            intf = candidate;
            for (int e=0;e<candidate.getEndpointCount();e++) {
                UsbEndpoint ep = candidate.getEndpoint(e);
                int addr = ep.getAddress() & 0xFF;
                if (addr==0x06) epOut06=ep;
                else if (addr==0x81) epIn81=ep;
                else if (addr==0x82) epIn82=ep;
                else if (addr==0x88) epIn88=ep;
            }
            if (epOut06 != null && epIn82 != null) break;
            connection.releaseInterface(candidate);
            intf = null;
        }
        if (intf == null) { listener.error("لم أجد واجهة USB المناسبة"); close(); return; }
        listener.log(String.format(Locale.US,"SOPIX %04X:%04X متصل — EP06/81/82/88 جاهزة", d.getVendorId(), d.getProductId()));
        listener.connected(true);
    }

    public boolean isOpen() { return connection != null && epOut06 != null && epIn82 != null; }

    private boolean send(byte[] data) {
        int n = connection.bulkTransfer(epOut06, data, data.length, 1500);
        return n == data.length;
    }

    public void sendHex(String hex) {
        if (!isOpen()) { listener.error("الحساس غير متصل"); return; }
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if ((clean.length() & 1) != 0) { listener.error("HEX غير صالح"); return; }
        byte[] data = new byte[clean.length()/2];
        for (int i=0;i<data.length;i++) data[i]=(byte)Integer.parseInt(clean.substring(i*2,i*2+2),16);
        boolean ok = send(data);
        listener.log((ok?"تم الإرسال: ":"فشل الإرسال: ") + hex);
    }

    public void initializeAndCapture(File output, int expectedBytes) {
        if (!isOpen()) { listener.error("الحساس غير متصل"); return; }
        if (!running.compareAndSet(false,true)) { listener.error("هناك عملية جارية حاليًا"); return; }

        new Thread(() -> {
            int total = 0;
            byte[] imageBuffer = new byte[Math.max(16384, epIn82.getMaxPacketSize()*256)];
            byte[] statusBuffer = new byte[64];
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                listener.progress(3,"بدء التهيئة");

                // Start readers before replaying the Windows sequence.
                long start = System.currentTimeMillis();
                int stepCount = SopixProtocol.CAPTURE_SEQUENCE.length;
                for (int i=0; i<stepCount && running.get(); i++) {
                    SopixProtocol.Step step = SopixProtocol.CAPTURE_SEQUENCE[i];
                    if (step.delayMs > 0) try { Thread.sleep(step.delayMs); } catch (InterruptedException ignored) {}
                    if (!send(step.data)) throw new IOException("فشل أمر التهيئة رقم " + (i+1));
                    if (epIn81 != null) connection.bulkTransfer(epIn81, statusBuffer, statusBuffer.length, 2);
                    if (i % 20 == 0) listener.progress(5 + (i * 25 / stepCount), "تهيئة الحساس");
                }

                listener.progress(32,"بانتظار التعرض الشعاعي");
                int idleMs = 0;
                boolean started = false;
                while (running.get() && total < expectedBytes) {
                    int n = connection.bulkTransfer(epIn82, imageBuffer, imageBuffer.length, 250);
                    if (n > 0) {
                        started = true;
                        idleMs = 0;
                        out.write(imageBuffer,0,n);
                        total += n;
                        int pct = 32 + Math.min(66, (int)((total * 66L) / expectedBytes));
                        listener.progress(pct,"استقبال الصورة");
                    } else {
                        idleMs += 250;
                        if (!started && System.currentTimeMillis() - start > 30000) throw new IOException("لم تصل صورة خلال 30 ثانية");
                        if (started && idleMs >= 1800) break;
                    }
                }
                out.flush();
                if (total == 0) throw new IOException("لم تصل بيانات من EP 0x82");
                listener.progress(100,"اكتمل الالتقاط");
                listener.rawSaved(output,total);
            } catch (Exception ex) {
                if (output.exists() && output.length()==0) output.delete();
                listener.error(ex.getMessage()==null ? "فشل الالتقاط" : ex.getMessage());
            } finally {
                running.set(false);
            }
        }, "sopix-auto-capture").start();
    }

    public void stopCapture() { running.set(false); listener.progress(0,"تم الإيقاف"); }

    public void close() {
        running.set(false);
        if (connection != null && intf != null) try { connection.releaseInterface(intf); } catch (Exception ignored) {}
        if (connection != null) try { connection.close(); } catch (Exception ignored) {}
        connection=null; intf=null; epOut06=null; epIn81=null; epIn82=null; epIn88=null;
        listener.connected(false);
    }
}
