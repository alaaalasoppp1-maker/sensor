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
    private final Object usbLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface intf;
    private UsbEndpoint epOut06, epIn81, epIn82, epIn88;

    public SopixUsb(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    public UsbDevice find() {
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    public boolean isRunning() { return running.get(); }

    public void requestOrOpen() {
        if (running.get()) {
            listener.log("تم تجاهل إعادة الاتصال أثناء جلسة التصوير");
            return;
        }
        UsbDevice found = find();
        if (found == null) { listener.error("لم يتم العثور على حساس SOPIX T1"); return; }
        device = found;
        if (!manager.hasPermission(found)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, 0, new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            manager.requestPermission(found, pi);
            listener.log("بانتظار منح صلاحية USB");
        } else open(found);
    }

    public void open(UsbDevice d) {
        if (running.get()) {
            listener.log("الاتصال الحالي محجوز لجلسة التصوير");
            return;
        }
        synchronized (usbLock) {
            closeLocked();
            device = d;
            UsbDeviceConnection newConnection = manager.openDevice(d);
            if (newConnection == null) { listener.error("تعذر فتح اتصال USB"); return; }

            UsbInterface selected = null;
            UsbEndpoint out06 = null, in81 = null, in82 = null, in88 = null;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface candidate = d.getInterface(i);
                if (!newConnection.claimInterface(candidate, true)) continue;
                UsbEndpoint candidateOut = null, candidate81 = null, candidate82 = null, candidate88 = null;
                for (int e = 0; e < candidate.getEndpointCount(); e++) {
                    UsbEndpoint ep = candidate.getEndpoint(e);
                    int addr = ep.getAddress() & 0xFF;
                    if (addr == 0x06) candidateOut = ep;
                    else if (addr == 0x81) candidate81 = ep;
                    else if (addr == 0x82) candidate82 = ep;
                    else if (addr == 0x88) candidate88 = ep;
                }
                if (candidateOut != null && candidate82 != null) {
                    selected = candidate;
                    out06 = candidateOut; in81 = candidate81; in82 = candidate82; in88 = candidate88;
                    break;
                }
                try { newConnection.releaseInterface(candidate); } catch (Exception ignored) {}
            }

            if (selected == null) {
                try { newConnection.close(); } catch (Exception ignored) {}
                listener.error("لم أجد واجهة USB المناسبة");
                return;
            }

            connection = newConnection;
            intf = selected;
            epOut06 = out06; epIn81 = in81; epIn82 = in82; epIn88 = in88;
            listener.log(String.format(Locale.US,
                    "SOPIX %04X:%04X متصل — EP06=%d EP82=%d",
                    d.getVendorId(), d.getProductId(), epOut06.getMaxPacketSize(), epIn82.getMaxPacketSize()));
            listener.connected(true);
        }
    }

    public boolean isOpen() {
        synchronized (usbLock) {
            return connection != null && intf != null && epOut06 != null && epIn82 != null;
        }
    }

    private int transferOut(UsbDeviceConnection c, UsbEndpoint ep, byte[] data, int timeoutMs) {
        if (c == null || ep == null) return -999;
        try { return c.bulkTransfer(ep, data, data.length, timeoutMs); }
        catch (Exception ex) { listener.log("خطأ EP06: " + ex.getMessage()); return -998; }
    }

    private boolean send(UsbDeviceConnection c, UsbEndpoint ep, byte[] data) {
        for (int attempt = 1; attempt <= 3 && running.get(); attempt++) {
            int n = transferOut(c, ep, data, 2200);
            if (n == data.length) return true;
            listener.log("EP06 محاولة " + attempt + " أعادت " + n + " بدل " + data.length);
            sleep(80L * attempt);
        }
        return false;
    }

    public void sendHex(String hex) {
        if (running.get()) { listener.error("أوقف جلسة التصوير قبل الإرسال اليدوي"); return; }
        final UsbDeviceConnection c;
        final UsbEndpoint out;
        synchronized (usbLock) { c = connection; out = epOut06; }
        if (c == null || out == null) { listener.error("الحساس غير متصل"); return; }
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if ((clean.length() & 1) != 0 || clean.isEmpty()) { listener.error("HEX غير صالح"); return; }
        byte[] data = new byte[clean.length()/2];
        try {
            for (int i=0;i<data.length;i++) data[i]=(byte)Integer.parseInt(clean.substring(i*2,i*2+2),16);
        } catch (NumberFormatException ex) { listener.error("HEX غير صالح"); return; }
        int n = transferOut(c, out, data, 2200);
        listener.log((n == data.length ? "تم الإرسال: " : "فشل الإرسال: ") + hex);
    }

    public void initializeAndCapture(File output, int expectedBytes) {
        if (!running.compareAndSet(false, true)) { listener.error("هناك جلسة تصوير جارية"); return; }

        final UsbDeviceConnection sessionConnection;
        final UsbEndpoint sessionOut;
        final UsbEndpoint sessionIn82;
        synchronized (usbLock) {
            sessionConnection = connection;
            sessionOut = epOut06;
            sessionIn82 = epIn82;
        }
        if (sessionConnection == null || sessionOut == null || sessionIn82 == null) {
            running.set(false);
            listener.error("الحساس غير متصل. اضغط فحص الاتصال أولًا");
            return;
        }

        new Thread(() -> {
            int total = 0;
            byte[] imageBuffer = new byte[Math.max(32768, sessionIn82.getMaxPacketSize() * 256)];
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                listener.progress(2, "تثبيت جلسة USB");
                int stepCount = SopixProtocol.CAPTURE_SEQUENCE.length;
                for (int i = 0; i < stepCount && running.get(); i++) {
                    SopixProtocol.Step step = SopixProtocol.CAPTURE_SEQUENCE[i];
                    sleep(Math.max(0, step.delayMs));
                    if (!send(sessionConnection, sessionOut, step.data)) {
                        throw new IOException("فشل أمر التهيئة رقم " + (i + 1));
                    }
                    if (i % Math.max(1, stepCount / 20) == 0) {
                        listener.progress(4 + (i * 24 / stepCount), "تهيئة الحساس");
                    }
                }
                if (!running.get()) throw new IOException("تم إيقاف الجلسة");

                listener.progress(30, "الحساس جاهز — قم بالتعريض الآن");
                long waitStart = System.currentTimeMillis();
                long lastDataAt = 0;
                boolean started = false;

                while (running.get() && total < expectedBytes) {
                    int n;
                    try { n = sessionConnection.bulkTransfer(sessionIn82, imageBuffer, imageBuffer.length, 300); }
                    catch (Exception ex) { throw new IOException("تعطل استقبال USB: " + ex.getMessage(), ex); }

                    if (n > 0) {
                        started = true;
                        lastDataAt = System.currentTimeMillis();
                        out.write(imageBuffer, 0, n);
                        total += n;
                        int pct = 30 + Math.min(69, (int)((total * 69L) / expectedBytes));
                        listener.progress(pct, String.format(Locale.US, "استقبال الصورة %,d / %,d", total, expectedBytes));
                    } else {
                        long now = System.currentTimeMillis();
                        if (!started && now - waitStart > 60000) throw new IOException("لم تصل الصورة خلال 60 ثانية");
                        if (started && now - lastDataAt > 2200) break;
                    }
                }

                out.flush();
                if (total == 0) throw new IOException("لم تصل بيانات من EP 0x82");
                if (total < expectedBytes / 2) throw new IOException("وصلت صورة ناقصة: " + total + " بايت");
                listener.progress(100, "تم استلام الصورة");
                listener.rawSaved(output, total);
            } catch (Exception ex) {
                if (output.exists() && output.length() == 0) output.delete();
                listener.error(ex.getMessage() == null ? "فشل الالتقاط" : ex.getMessage());
            } finally {
                running.set(false);
            }
        }, "sopix-capture-session").start();
    }

    public void stopCapture() {
        if (running.getAndSet(false)) listener.progress(0, "تم إيقاف الجلسة");
    }

    public void close() {
        running.set(false);
        synchronized (usbLock) { closeLocked(); }
        listener.connected(false);
    }

    private void closeLocked() {
        if (connection != null && intf != null) {
            try { connection.releaseInterface(intf); } catch (Exception ignored) {}
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
        }
        connection = null; intf = null;
        epOut06 = null; epIn81 = null; epIn82 = null; epIn88 = null;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
