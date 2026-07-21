package com.dentalchain.sopix;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

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
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean statusPumpRunning = new AtomicBoolean(false);

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface intf;
    private UsbEndpoint epOut06;
    private UsbEndpoint epIn81;
    private UsbEndpoint epIn82;
    private UsbEndpoint epIn88;
    private Thread statusThread;

    public SopixUsb(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public UsbDevice find() {
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    public void requestOrOpen() {
        device = find();
        if (device == null) {
            listener.error("لم يتم العثور على حساس SOPIX T1");
            return;
        }
        if (!manager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_MUTABLE
            );
            manager.requestPermission(device, pi);
            listener.log("تم طلب صلاحية USB");
        } else {
            open(device);
        }
    }

    public synchronized void open(UsbDevice d) {
        closeInternal(false);
        device = d;
        connection = manager.openDevice(d);
        if (connection == null) {
            listener.error("تعذر فتح اتصال USB");
            return;
        }

        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface candidate = d.getInterface(i);
            if (!connection.claimInterface(candidate, true)) continue;

            UsbEndpoint out06 = null;
            UsbEndpoint in81 = null;
            UsbEndpoint in82 = null;
            UsbEndpoint in88 = null;

            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint ep = candidate.getEndpoint(e);
                int address = ep.getAddress() & 0xFF;
                if (address == 0x06) out06 = ep;
                else if (address == 0x81) in81 = ep;
                else if (address == 0x82) in82 = ep;
                else if (address == 0x88) in88 = ep;
            }

            if (out06 != null && in81 != null && in82 != null && in88 != null) {
                intf = candidate;
                epOut06 = out06;
                epIn81 = in81;
                epIn82 = in82;
                epIn88 = in88;
                break;
            }

            connection.releaseInterface(candidate);
        }

        if (intf == null) {
            listener.error("لم أجد واجهة USB الكاملة 06/81/82/88");
            closeInternal(false);
            return;
        }

        listener.log(String.format(
                Locale.US,
                "متصل %04X:%04X — EP06 OUT، EP81/82/88 IN",
                d.getVendorId(),
                d.getProductId()
        ));
        listener.connected(true);
    }

    public boolean isOpen() {
        return connection != null && intf != null && epOut06 != null && epIn81 != null && epIn82 != null && epIn88 != null;
    }

    private static String hex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static void waitMicros(int micros) {
        if (micros <= 0) return;
        if (micros >= 2000) {
            long millis = micros / 1000L;
            int nanos = (micros % 1000) * 1000;
            try {
                Thread.sleep(millis, nanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            LockSupport.parkNanos(micros * 1000L);
        }
    }

    private void startStatusPump() {
        if (epIn81 == null || connection == null) return;
        if (!statusPumpRunning.compareAndSet(false, true)) return;

        statusThread = new Thread(() -> {
            byte[] buffer = new byte[Math.max(64, epIn81.getMaxPacketSize())];
            int last0 = -1;
            int last1 = -1;
            while (statusPumpRunning.get() && connection != null) {
                UsbDeviceConnection c = connection;
                if (c == null) break;
                int n = c.bulkTransfer(epIn81, buffer, buffer.length, 80);
                if (n > 0) {
                    int b0 = buffer[0] & 0xFF;
                    int b1 = n > 1 ? buffer[1] & 0xFF : -1;
                    if (b0 != last0 || b1 != last1) {
                        listener.log("EP81: " + hex(buffer, Math.min(n, 12)));
                        last0 = b0;
                        last1 = b1;
                    }
                }
            }
        }, "sopix-status-81");
        statusThread.start();
    }

    private void stopStatusPump() {
        statusPumpRunning.set(false);
        if (statusThread != null) {
            try {
                statusThread.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            statusThread = null;
        }
    }

    private int sendOnce(byte[] data, int timeoutMs) {
        UsbDeviceConnection c = connection;
        UsbEndpoint ep = epOut06;
        if (c == null || ep == null) return -999;
        return c.bulkTransfer(ep, data, data.length, timeoutMs);
    }

    private int read88(byte[] buffer, int timeoutMs) {
        UsbDeviceConnection c = connection;
        UsbEndpoint ep = epIn88;
        if (c == null || ep == null) return -999;
        return c.bulkTransfer(ep, buffer, buffer.length, timeoutMs);
    }

    private void exchange(SopixProtocol.Step step, int index) throws IOException {
        int written = sendOnce(step.data, 1500);
        if (written != step.data.length) {
            throw new IOException("فشل إرسال الأمر " + index + " (USB=" + written + ")");
        }

        int expectedSequence = step.sequence();
        byte[] response = new byte[Math.max(1024, epIn88.getMaxPacketSize() * 2)];
        long deadline = System.currentTimeMillis() + 2500;

        while (running.get() && System.currentTimeMillis() < deadline) {
            int n = read88(response, 250);
            if (n < 0) continue;
            if (n == 0) continue;

            if (n == 2 && (response[0] & 0xFF) == expectedSequence) {
                int status = response[1] & 0xFF;
                if (status == 0x00 || status == 0x02) return;
                throw new IOException(String.format(
                        Locale.US,
                        "الحساس رفض الأمر %d — ACK %02X %02X",
                        index,
                        expectedSequence,
                        status
                ));
            }

            listener.log("EP88 data " + n + "B: " + hex(response, Math.min(n, 24)));
        }

        throw new IOException(String.format(
                Locale.US,
                "لا يوجد ACK للأمر %d (%02X)",
                index,
                expectedSequence
        ));
    }

    public void sendHex(String input) {
        if (!isOpen()) {
            listener.error("الحساس غير متصل");
            return;
        }

        String clean = input.replaceAll("[^0-9A-Fa-f]", "");
        if ((clean.length() & 1) != 0 || clean.isEmpty()) {
            listener.error("HEX غير صالح");
            return;
        }

        byte[] data = new byte[clean.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }

        new Thread(() -> {
            int n = sendOnce(data, 1500);
            listener.log((n == data.length ? "تم الإرسال: " : "فشل الإرسال: ") + input + " (" + n + ")");
        }, "sopix-manual-send").start();
    }

    public void initializeAndCapture(File output, int expectedBytes) {
        if (!isOpen()) {
            listener.error("الحساس غير متصل");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            listener.error("هناك عملية جارية حاليًا");
            return;
        }

        new Thread(() -> {
            final AtomicInteger total = new AtomicInteger(0);
            final AtomicBoolean imageStarted = new AtomicBoolean(false);
            final AtomicBoolean imageDone = new AtomicBoolean(false);
            final AtomicBoolean imageFailed = new AtomicBoolean(false);

            Thread imageThread = null;
            try {
                startStatusPump();
                listener.progress(2, "تثبيت اتصال الحساس");
                Thread.sleep(300);

                imageThread = new Thread(() -> {
                    byte[] imageBuffer = new byte[Math.max(16384, epIn82.getMaxPacketSize() * 32)];
                    int idleAfterStartMs = 0;
                    long startedAt = System.currentTimeMillis();

                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                        while (running.get()) {
                            UsbDeviceConnection c = connection;
                            if (c == null) break;
                            int n = c.bulkTransfer(epIn82, imageBuffer, imageBuffer.length, 220);

                            if (n > 0) {
                                imageStarted.set(true);
                                idleAfterStartMs = 0;
                                out.write(imageBuffer, 0, n);
                                int value = total.addAndGet(n);
                                int pct = 50 + Math.min(48, (int) ((value * 48L) / Math.max(1, expectedBytes)));
                                listener.progress(pct, "استقبال الصورة");
                            } else if (imageStarted.get()) {
                                idleAfterStartMs += 220;
                                if (idleAfterStartMs >= 1600) break;
                            } else if (System.currentTimeMillis() - startedAt > 45000) {
                                break;
                            }
                        }
                        out.flush();
                    } catch (Exception e) {
                        imageFailed.set(true);
                        listener.log("قارئ EP82: " + e.getMessage());
                    } finally {
                        imageDone.set(true);
                    }
                }, "sopix-image-82");
                imageThread.start();

                listener.progress(5, "بدء تسلسل التهيئة الحقيقي");
                int count = SopixProtocol.CAPTURE_SEQUENCE.length;
                long targetNanos = System.nanoTime();
                for (int i = 0; i < count && running.get(); i++) {
                    SopixProtocol.Step step = SopixProtocol.CAPTURE_SEQUENCE[i];
                    targetNanos += step.delayUs * 1000L;
                    long remaining;
                    while ((remaining = targetNanos - System.nanoTime()) > 0) {
                        if (remaining >= 2_000_000L) {
                            waitMicros((int)Math.min(Integer.MAX_VALUE, remaining / 1000L));
                        } else {
                            LockSupport.parkNanos(remaining);
                        }
                    }
                    exchange(step, i + 1);
                    if (i % 8 == 0 || i + 1 == count) {
                        int pct = 5 + (i * 42 / count);
                        listener.progress(pct, "تهيئة " + (i + 1) + "/" + count);
                    }
                }

                if (!running.get()) throw new IOException("تم إيقاف العملية");
                listener.progress(48, "بانتظار اكتمال الصورة");

                long waitUntil = System.currentTimeMillis() + 12000;
                while (!imageDone.get() && System.currentTimeMillis() < waitUntil && running.get()) {
                    Thread.sleep(100);
                }

                running.set(false);
                if (imageThread != null) imageThread.join(2500);

                if (imageFailed.get()) throw new IOException("حدث خطأ أثناء قراءة EP 0x82");
                if (total.get() <= 0) throw new IOException("تمت التهيئة لكن لم تصل بيانات من EP 0x82");

                listener.progress(100, "اكتمل الالتقاط");
                listener.rawSaved(output, total.get());
            } catch (Exception ex) {
                running.set(false);
                if (imageThread != null) {
                    try {
                        imageThread.join(1200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (output.exists() && output.length() == 0) output.delete();
                listener.error(ex.getMessage() == null ? "فشل الالتقاط" : ex.getMessage());
            } finally {
                stopStatusPump();
                running.set(false);
            }
        }, "sopix-capture").start();
    }

    public void stopCapture() {
        running.set(false);
        listener.progress(0, "تم الإيقاف");
    }

    public synchronized void close() {
        closeInternal(true);
    }

    private void closeInternal(boolean notify) {
        running.set(false);
        stopStatusPump();

        if (connection != null && intf != null) {
            try {
                connection.releaseInterface(intf);
            } catch (Exception ignored) {
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }

        connection = null;
        intf = null;
        epOut06 = null;
        epIn81 = null;
        epIn82 = null;
        epIn88 = null;

        if (notify) listener.connected(false);
    }
}
