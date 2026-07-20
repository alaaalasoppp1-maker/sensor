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
    private final AtomicBoolean reading = new AtomicBoolean(false);

    public SopixUsb(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public UsbDevice find() {
        for (UsbDevice d : manager.getDeviceList().values()) {
            listener.log(String.format(Locale.US, "USB: %04X:%04X %s", d.getVendorId(), d.getProductId(), d.getDeviceName()));
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    public void requestOrOpen() {
        device = find();
        if (device == null) { listener.error("لم يتم العثور على SOPIX T1"); return; }
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
        listener.log("Interfaces: " + d.getInterfaceCount());
        for (int i=0;i<d.getInterfaceCount();i++) {
            UsbInterface candidate = d.getInterface(i);
            listener.log("Interface " + i + " endpoints=" + candidate.getEndpointCount());
            if (!connection.claimInterface(candidate, true)) continue;
            intf = candidate;
            for (int e=0;e<candidate.getEndpointCount();e++) {
                UsbEndpoint ep = candidate.getEndpoint(e);
                int addr = ep.getAddress() & 0xFF;
                listener.log(String.format(Locale.US,"EP 0x%02X type=%d dir=%s max=%d", addr, ep.getType(), ep.getDirection()==UsbConstants.USB_DIR_IN?"IN":"OUT", ep.getMaxPacketSize()));
                if (addr==0x06) epOut06=ep;
                else if (addr==0x81) epIn81=ep;
                else if (addr==0x82) epIn82=ep;
                else if (addr==0x88) epIn88=ep;
            }
            if (epIn82 != null) break;
            connection.releaseInterface(candidate);
            intf = null;
        }
        if (intf == null) { listener.error("لم أجد واجهة USB المناسبة"); close(); return; }
        listener.log("تم فتح الحساس بنجاح");
        listener.connected(true);
    }

    public boolean isOpen() { return connection != null && epIn82 != null; }

    public void sendHex(String hex) {
        if (connection == null || epOut06 == null) { listener.error("Endpoint 0x06 غير متاح"); return; }
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if ((clean.length() & 1) != 0) { listener.error("HEX غير صالح"); return; }
        byte[] data = new byte[clean.length()/2];
        for (int i=0;i<data.length;i++) data[i]=(byte)Integer.parseInt(clean.substring(i*2,i*2+2),16);
        int n = connection.bulkTransfer(epOut06, data, data.length, 1500);
        listener.log("OUT 0x06: " + n + " bytes  " + hex);
    }

    public void captureRaw(File output, int expectedBytes, int idleTimeoutMs) {
        if (!isOpen()) { listener.error("الحساس غير متصل"); return; }
        if (!reading.compareAndSet(false,true)) { listener.error("الالتقاط يعمل حاليًا"); return; }
        new Thread(() -> {
            int total=0, idle=0;
            byte[] buffer = new byte[Math.max(16384, epIn82.getMaxPacketSize()*256)];
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                listener.log("بدء القراءة من EP 0x82...");
                while (reading.get() && total < expectedBytes) {
                    int n = connection.bulkTransfer(epIn82, buffer, buffer.length, 250);
                    if (n > 0) { out.write(buffer,0,n); total += n; idle=0; if (total % 262144 < n) listener.log("RAW: " + total + " bytes"); }
                    else { idle += 250; if (total > 0 && idle >= idleTimeoutMs) break; }
                }
                out.flush();
                listener.log("انتهت القراءة: " + total + " bytes");
                listener.rawSaved(output,total);
            } catch (Exception ex) { listener.error("Capture: " + ex.getMessage()); }
            finally { reading.set(false); }
        }, "sopix-capture").start();
    }

    public void stopCapture() { reading.set(false); }

    public void close() {
        reading.set(false);
        if (connection != null && intf != null) try { connection.releaseInterface(intf); } catch (Exception ignored) {}
        if (connection != null) try { connection.close(); } catch (Exception ignored) {}
        connection=null; intf=null; epOut06=null; epIn81=null; epIn82=null; epIn88=null;
        listener.connected(false);
    }
}
