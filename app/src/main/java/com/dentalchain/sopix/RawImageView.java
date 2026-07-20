package com.dentalchain.sopix;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.io.*;

public class RawImageView extends View {
    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float scale=1f, tx=0f, ty=0f, lastX, lastY;
    private ScaleGestureDetector scaler;

    public RawImageView(Context c) {
        super(c); setBackgroundColor(Color.BLACK);
        scaler = new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(ScaleGestureDetector d){ scale=Math.max(0.5f,Math.min(8f,scale*d.getScaleFactor())); invalidate(); return true; }
        });
    }

    public void loadRaw16(File f, int width, int height, float contrast, float brightness) throws IOException {
        int count=width*height;
        byte[] data=new byte[count*2];
        try(FileInputStream in=new FileInputStream(f)){ int off=0,n; while(off<data.length && (n=in.read(data,off,data.length-off))>0) off+=n; if(off<data.length) throw new EOFException("الملف أصغر من الصورة المتوقعة"); }
        int[] hist=new int[65536]; int[] vals=new int[count];
        for(int i=0;i<count;i++){ int v=(data[i*2]&255)|((data[i*2+1]&255)<<8); vals[i]=v; hist[v]++; }
        int low=0,high=65535,cut=Math.max(1,count/200); int s=0;
        while(low<65535 && (s+=hist[low])<cut) low++;
        s=0; while(high>0 && (s+=hist[high])<cut) high--;
        int[] px=new int[count]; float range=Math.max(1,high-low);
        for(int i=0;i<count;i++){ float x=(vals[i]-low)/range; x=(x-.5f)*contrast+.5f+brightness; int g=(int)(Math.max(0,Math.min(1,x))*255); px[i]=Color.rgb(g,g,g); }
        bitmap=Bitmap.createBitmap(px,width,height,Bitmap.Config.ARGB_8888); scale=1f;tx=ty=0; invalidate();
    }

    protected void onDraw(Canvas c){ super.onDraw(c); if(bitmap==null)return; float fit=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight()); c.save(); c.translate(getWidth()/2f+tx,getHeight()/2f+ty); c.scale(fit*scale,fit*scale); c.drawBitmap(bitmap,-bitmap.getWidth()/2f,-bitmap.getHeight()/2f,paint); c.restore(); }
    public boolean onTouchEvent(android.view.MotionEvent e){ scaler.onTouchEvent(e); if(!scaler.isInProgress()){ if(e.getAction()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();} else if(e.getAction()==MotionEvent.ACTION_MOVE){tx+=e.getX()-lastX;ty+=e.getY()-lastY;lastX=e.getX();lastY=e.getY();invalidate();}} return true; }
}
