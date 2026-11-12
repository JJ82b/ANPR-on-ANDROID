package cn.platejournal;

import android.content.Context;
import android.hardware.camera2.*;
import java.util.*;

/** Only IDs that Android exposes for independent opening. */
final class CameraOptions {
    static final class Entry {
        final String id,label; final boolean rear;
        Entry(String id,String label,boolean rear){this.id=id;this.label=label;this.rear=rear;}
    }
    static ArrayList<Entry> list(Context context) throws Exception {
        CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        ArrayList<Entry> result=new ArrayList<>();
        for(String id:manager.getCameraIdList()){
            CameraCharacteristics c=manager.getCameraCharacteristics(id);
            Integer facing=c.get(CameraCharacteristics.LENS_FACING);
            boolean rear=Objects.equals(facing,CameraCharacteristics.LENS_FACING_BACK);
            String name=rear?"后置":Objects.equals(facing,CameraCharacteristics.LENS_FACING_FRONT)?"前置":"外接 / 其他";
            float[] lengths=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            String focal=lengths!=null&&lengths.length>0?String.format(Locale.ROOT," · %.1f mm",lengths[0]):"";
            result.add(new Entry(id,name+" · ID "+id+focal,rear));
        }
        return result;
    }
}
