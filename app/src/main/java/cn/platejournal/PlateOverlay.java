package cn.platejournal;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import com.hyperai.hyperlpr3.bean.Plate;

final class PlateOverlay extends View {
    private Plate[] plates=new Plate[0]; private int imageWidth=1,imageHeight=1;
    private long expires; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    PlateOverlay(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void set(Plate[] p,int w,int h){plates=p;imageWidth=w;imageHeight=h;expires=SystemClock.uptimeMillis()+900;invalidate();postDelayed(this::invalidate,910);}
    void clear(){plates=new Plate[0];invalidate();}
    protected void onDraw(Canvas c){
        if(SystemClock.uptimeMillis()>expires)return;
        float sx=getWidth()/(float)imageWidth, sy=getHeight()/(float)imageHeight;
        for(Plate p:plates){
            float x=Math.max(0,p.getX1()*sx),y=Math.max(0,p.getY1()*sy);
            paint.setColor(Color.rgb(65,229,170));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*getResources().getDisplayMetrics().density);
            c.drawRect(x,y,Math.min(getWidth(),p.getX2()*sx),Math.min(getHeight(),p.getY2()*sy),paint);
            paint.setStyle(Paint.Style.FILL);paint.setTextSize(16*getResources().getDisplayMetrics().scaledDensity);
            String code=p.getCode();float tw=paint.measureText(code)+12;float top=Math.max(0,y-paint.getTextSize()-10);
            float left=Math.min(x,Math.max(0,getWidth()-tw)); c.drawRect(left,top,left+tw,top+paint.getTextSize()+10,paint);
            paint.setColor(Color.rgb(12,24,20));c.drawText(code,left+6,top+paint.getTextSize()+2,paint);
        }
    }
}
