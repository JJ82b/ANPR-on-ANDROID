package cn.platejournal;

import android.content.Context;
import android.hardware.camera2.*;
import android.util.Range;
import java.util.*;

final class ExposureOptions {
    static final long FRAME_NS=33_333_333L;
    boolean supported;
    final ArrayList<Integer> isos=new ArrayList<>();
    final ArrayList<Long> shutters=new ArrayList<>();
    static ExposureOptions read(Context context,String cameraId) throws Exception {
        CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        return read(manager.getCameraCharacteristics(cameraId));
    }
    static ExposureOptions read(CameraCharacteristics c){
        ExposureOptions out=new ExposureOptions();
        // Some Camera2 devices expose working ranges without MANUAL_SENSOR or a complete key list.
        Range<Integer> iso=c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> time=c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if(iso==null||time==null||iso.getLower()<=0||time.getLower()<=0)return out;
        TreeSet<Integer> values=new TreeSet<>();values.add(iso.getLower());values.add(iso.getUpper());
        for(int x:new int[]{50,64,100,125,160,200,250,320,400,500,640,800,1000,1250,1600,2000,2500,3200,6400,12800})if(iso.contains(x))values.add(x);
        out.isos.addAll(values);
        TreeSet<Long> times=new TreeSet<>(Collections.reverseOrder());long upper=Math.min(time.getUpper(),FRAME_NS);
        if(time.getLower()<=upper){times.add(time.getLower());times.add(upper);}
        for(int denominator:new int[]{30,40,50,60,80,100,120,125,160,200,250,320,400,500,640,800,1000,1250,1600,2000,2500,3200,4000,8000}){
            long ns=1_000_000_000L/denominator;if(time.contains(ns)&&ns<=FRAME_NS)times.add(ns);
        }
        out.shutters.addAll(times);out.supported=!out.isos.isEmpty()&&!out.shutters.isEmpty();return out;
    }
    void validate(int iso,long ns){if(iso==0&&ns==0)return;if(!supported||!isos.contains(iso)||!shutters.contains(ns))throw new IllegalArgumentException("所选 ISO 或快门不受支持，请在设置中重新选择或使用自动曝光");}
    static String shutter(long ns){return ns<=0?"自动":"1/"+Math.max(1,Math.round(1_000_000_000.0/ns))+" 秒";}
}
