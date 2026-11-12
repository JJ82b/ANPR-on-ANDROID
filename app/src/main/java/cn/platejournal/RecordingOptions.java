package cn.platejournal;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.util.Size;
import java.util.*;

/** Query the selected camera and AVC surface encoder used for recording. */
final class RecordingOptions {
    static ArrayList<Size> supported(Context context,String cameraId) throws Exception {
        CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        StreamConfigurationMap map=manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        ArrayList<Size> result=new ArrayList<>();
        if(map==null||map.getOutputSizes(MediaRecorder.class)==null)return result;
        ArrayList<MediaCodecInfo.VideoCapabilities> codecs=encoders();
        for(Size size:map.getOutputSizes(MediaRecorder.class)) {
            long duration=map.getOutputMinFrameDuration(MediaRecorder.class,size);
            if(duration>34_000_000L||previewFor(map,size)==null)continue;
            for(MediaCodecInfo.VideoCapabilities codec:codecs) {
                if(codec.areSizeAndRateSupported(size.getWidth(),size.getHeight(),30)){result.add(size);break;}
            }
        }
        result.sort((a,b)->Long.compare((long)b.getWidth()*b.getHeight(),(long)a.getWidth()*a.getHeight()));
        return new ArrayList<>(new LinkedHashSet<>(result));
    }
    private static ArrayList<MediaCodecInfo.VideoCapabilities> encoders(){
        ArrayList<MediaCodecInfo.VideoCapabilities> codecs=new ArrayList<>();
        for(MediaCodecInfo codec:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if(!codec.isEncoder())continue;
            try {
                MediaCodecInfo.CodecCapabilities cap=codec.getCapabilitiesForType("video/avc");
                boolean surface=false;for(int color:cap.colorFormats)if(color==MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)surface=true;
                if(surface)codecs.add(cap.getVideoCapabilities());
            }catch(IllegalArgumentException ignored){}
        }
        return codecs;
    }
    static Size previewFor(StreamConfigurationMap map,Size video) {
        Size[] sizes=map.getOutputSizes(SurfaceTexture.class);if(sizes==null)return null;
        int[][] dimensions=new int[sizes.length][2];for(int i=0;i<sizes.length;i++){dimensions[i][0]=sizes[i].getWidth();dimensions[i][1]=sizes[i].getHeight();}
        int selected=PreviewGeometry.previewIndex(dimensions,video.getWidth(),video.getHeight());
        return selected<0?null:sizes[selected];
    }
    static int bitrate(Size size){
        int desired=(int)Math.max(2_000_000L,Math.min(60_000_000L,(long)size.getWidth()*size.getHeight()*4));
        for(MediaCodecInfo.VideoCapabilities cap:encoders())if(cap.areSizeAndRateSupported(size.getWidth(),size.getHeight(),30))return cap.getBitrateRange().clamp(desired);
        throw new IllegalStateException("没有支持所选尺寸的编码器");
    }
    static boolean acceptsBitrate(Size size,int bitrate){
        for(MediaCodecInfo.VideoCapabilities cap:encoders())if(cap.areSizeAndRateSupported(size.getWidth(),size.getHeight(),30)&&cap.getBitrateRange().contains(bitrate))return true;
        return false;
    }
    static ArrayList<Integer> bitrates(Size size){
        TreeSet<Integer> choices=new TreeSet<>();
        for(int mbps:new int[]{1,2,4,6,8,10,12,16,20,24,32,40,50,60,80,100})if(acceptsBitrate(size,mbps*1_000_000))choices.add(mbps*1_000_000);
        choices.add(bitrate(size));ArrayList<Integer> out=new ArrayList<>();out.add(0);out.addAll(choices);return out;
    }
    static String label(Size size){
        String prefix=size.getWidth()==3840&&size.getHeight()==2160?"4K UHD":size.getWidth()==4096&&size.getHeight()==2160?"4K DCI":size.getHeight()+"p";
        return prefix+" · "+size.getWidth()+" × "+size.getHeight()+" · 30 fps";
    }
}
