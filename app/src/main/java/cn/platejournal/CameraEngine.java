package cn.platejournal;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import java.nio.ByteBuffer;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import java.io.File;
import java.util.*;

/** Independent raw analysis stream; compatible window-copy fallback if a device rejects three outputs. */
final class CameraEngine {
    interface Listener { void ready(int width,int height); void error(String text); void started(); boolean wantsFrame(); void frame(byte[] data,int width,int height,int rotation,long videoMs); }
    private final Activity activity; private final TextureView texture; private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private CameraDevice device; private CameraCaptureSession session; private MediaRecorder recorder;
    private Surface preview; private Size size,previewSize; private int orientation=90, generation=0;
    private boolean recording=false,frontFacing=false;
    private final String cameraId;
    private final int selectedWidth,selectedHeight;
    private int displayRotation,videoRotation,sessionGeneration=0;
    private ImageReader reader;private Size analysisSize;
    private CameraCharacteristics characteristics;private boolean rawActive=false;
    private final int requestedBitrate;private int requestedIso;private long requestedShutter;
    private CaptureRequest.Builder activeRequest;private CameraCaptureSession.CaptureCallback activeCallback;
    private int actualIso;private long actualShutter;
    private boolean torchEnabled,flashAvailable;
    boolean hasFlash(){return flashAvailable;}
    boolean torchEnabled(){return torchEnabled;}
    int actualIso(){return actualIso;}
    long actualShutter(){return actualShutter;}
    private String actualExposure="";
    private final FrameClock frameClock=new FrameClock();private long firstFrameElapsedNs=-1;
    long visibleFrameTimeMs(){return !recording||firstFrameElapsedNs<0?-1:Math.max(0,(android.os.SystemClock.elapsedRealtimeNanos()-firstFrameElapsedNs)/1_000_000L);}
    boolean rawAnalysis(){return rawActive;}
    String exposureStatus(){return actualExposure;}
    int recordingBitrate(){return requestedBitrate==0?RecordingOptions.bitrate(size):requestedBitrate;}

    CameraEngine(Activity a,TextureView t,String id,int w,int h,int bitrate,int iso,long shutter,boolean torch,Listener l) { cameraId=id; activity=a; texture=t; listener=l; selectedWidth=w;selectedHeight=h;requestedBitrate=bitrate;requestedIso=iso;requestedShutter=shutter;torchEnabled=torch; }
    int width(){return size==null?0:size.getWidth();}
    int height(){return size==null?0:size.getHeight();}
    boolean rotationChanged(){return size!=null&&activity.getWindowManager().getDefaultDisplay().getRotation()!=displayRotation;}
    void transform(){
        if(previewSize==null)return;
        int w=texture.getWidth(),h=texture.getHeight(); if(w==0||h==0)return;
        SurfaceTexture surface=texture.getSurfaceTexture();if(surface!=null)surface.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());
        Matrix matrix=new Matrix();RectF view=new RectF(0,0,w,h);float cx=w/2f,cy=h/2f;
        int naturalW=orientation%180==0?previewSize.getWidth():previewSize.getHeight();
        int naturalH=orientation%180==0?previewSize.getHeight():previewSize.getWidth();
        RectF buffer=new RectF(cx-naturalW/2f,cy-naturalH/2f,cx+naturalW/2f,cy+naturalH/2f);
        matrix.setRectToRect(view,buffer,Matrix.ScaleToFit.FILL);
        matrix.postRotate(-displayRotation*90,cx,cy);
        boolean swap=displayRotation%2!=0;
        float scale=Math.min(w/(float)(swap?naturalH:naturalW),h/(float)(swap?naturalW:naturalH));
        matrix.postScale(scale,scale,cx,cy);
        // SurfaceTexture camera preview mirrors front cameras; undo it so text matches raw analysis and video.
        if(frontFacing)matrix.postScale(-1,1,cx,cy);
        texture.setTransform(matrix);
    }
    @SuppressWarnings("MissingPermission") void open() {
        final int token=++generation;
        try {
            CameraManager m=(CameraManager)activity.getSystemService(Activity.CAMERA_SERVICE);
            String id=cameraId;CameraCharacteristics chars=m.getCameraCharacteristics(id);characteristics=chars;flashAvailable=Boolean.TRUE.equals(chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));if(!flashAvailable)torchEnabled=false;ExposureOptions.read(chars).validate(requestedIso,requestedShutter);
            orientation=chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            displayRotation=activity.getWindowManager().getDefaultDisplay().getRotation();
            frontFacing=Objects.equals(chars.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_FRONT);
            videoRotation=CameraRotation.degrees(orientation,displayRotation*90,frontFacing);
            StreamConfigurationMap map=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            size=new Size(selectedWidth,selectedHeight);
            if(!RecordingOptions.supported(activity,cameraId).contains(size))throw new Exception("设备不支持所选录像尺寸，请重新打开设置选择");
            previewSize=RecordingOptions.previewFor(map,size);
            if(previewSize==null)throw new Exception("没有可用的预览尺寸");
            analysisSize=null;Size[] yuv=map.getOutputSizes(ImageFormat.YUV_420_888);
            if(yuv!=null)for(Size candidate:yuv){
                if(candidate.getWidth()>1280||candidate.getHeight()>720||candidate.getWidth()>size.getWidth()||candidate.getHeight()>size.getHeight())continue;
                if((long)candidate.getWidth()*size.getHeight()!=(long)candidate.getHeight()*size.getWidth())continue;
                if(analysisSize==null||(long)candidate.getWidth()*candidate.getHeight()>(long)analysisSize.getWidth()*analysisSize.getHeight())analysisSize=candidate;
            }
            if(analysisSize!=null){reader=ImageReader.newInstance(analysisSize.getWidth(),analysisSize.getHeight(),ImageFormat.YUV_420_888,2);
                reader.setOnImageAvailableListener(source->{
                    try(Image image=source.acquireLatestImage()){
                        if(image==null||token!=generation||!rawActive||!listener.wantsFrame())return;
                        Rect crop=image.getCropRect();Image.Plane[] planes=image.getPlanes();ByteBuffer[] buffers=new ByteBuffer[3];int[] rows=new int[3],steps=new int[3];
                        for(int i=0;i<3;i++){buffers[i]=planes[i].getBuffer();rows[i]=planes[i].getRowStride();steps[i]=planes[i].getPixelStride();}
                        byte[] data=YuvPlanes.nv21(crop.width(),crop.height(),buffers,rows,steps,crop.left,crop.top);
                        listener.frame(data,crop.width(),crop.height(),videoRotation,recording?frameClock.relativeMs(image.getTimestamp()):-1);
                    }catch(Exception e){rawActive=false;android.util.Log.w("PlateCamera","Raw frame unavailable; using window copy",e);}
                },main);
            }

            m.openCamera(id,new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice c) { if(token!=generation){c.close();return;} device=c; configure(false); }
                public void onDisconnected(CameraDevice c) { c.close(); if(token==generation){device=null;listener.error("摄像头连接中断");} }
                public void onError(CameraDevice c,int e) { c.close(); if(token==generation){device=null;listener.error("摄像头不可用（"+e+"）");} }
            },main);
        } catch(Exception e) { listener.error(e.getMessage()); }
    }
    private void configure(boolean record) {configure(record,reader!=null);}
    private void configure(boolean record,boolean useRaw) {
        final int token=generation,sessionToken=++sessionGeneration;rawActive=false;
        try {
            if(session!=null){session.close();session=null;}
            if(preview!=null){preview.release();preview=null;}
            SurfaceTexture st=texture.getSurfaceTexture(); if(st==null || device==null)throw new Exception("摄像头尚未准备好");
            st.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight()); preview=new Surface(st);
            ArrayList<Surface> surfaces=new ArrayList<>(); surfaces.add(preview);
            CaptureRequest.Builder request=device.createCaptureRequest(record?CameraDevice.TEMPLATE_RECORD:CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(preview);
            int[] afModes=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            int af=CaptureRequest.CONTROL_AF_MODE_OFF;
            if(afModes!=null)for(int mode:afModes)if(mode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)af=mode;
            request.set(CaptureRequest.CONTROL_AF_MODE,af);
            request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
            applyExposure(request,requestedIso,requestedShutter);
            if(flashAvailable)request.set(CaptureRequest.FLASH_MODE,torchEnabled?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);
            if(useRaw&&reader!=null){surfaces.add(reader.getSurface());request.addTarget(reader.getSurface());}
            if(record){Surface output=recorder.getSurface();surfaces.add(output);request.addTarget(output);}
            device.createCaptureSession(surfaces,new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession s) {
                    if(token!=generation || sessionToken!=sessionGeneration || device==null){s.close();return;} session=s;
                    try {
                        if(record){frameClock.reset();firstFrameElapsedNs=-1;recorder.start();recording=true;}
                        activeRequest=request;activeCallback=new CameraCaptureSession.CaptureCallback(){
                            @Override public void onCaptureStarted(CameraCaptureSession ss,CaptureRequest q,long timestamp,long frameNumber){
                                if(token!=generation||sessionToken!=sessionGeneration||!recording||!record)return;
                                frameClock.firstFrame(timestamp);
                                if(firstFrameElapsedNs<0){Integer source=characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);firstFrameElapsedNs=source!=null&&source==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME?timestamp:android.os.SystemClock.elapsedRealtimeNanos();}
                            }
                            @Override public void onCaptureCompleted(CameraCaptureSession ss,CaptureRequest q,TotalCaptureResult result){
                                if(token!=generation||sessionToken!=sessionGeneration)return;
                                Integer iso=result.get(CaptureResult.SENSOR_SENSITIVITY);Long shutter=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                                if(iso!=null&&shutter!=null){actualIso=iso;actualShutter=shutter;actualExposure="ISO "+iso+" · "+ExposureOptions.shutter(shutter);}
                            }
                        };s.setRepeatingRequest(request.build(),activeCallback,main);rawActive=useRaw;
                        if(record){listener.started();}
                        else listener.ready(videoRotation%180==0?size.getWidth():size.getHeight(),videoRotation%180==0?size.getHeight():size.getWidth());
                    } catch(Exception e){listener.error("摄像头启动失败："+e.getMessage());}
                }
                public void onConfigureFailed(CameraCaptureSession s){s.close();if(token!=generation||sessionToken!=sessionGeneration)return;
                    if(useRaw)configure(record,false);else listener.error("摄像头不支持当前录制配置，请降低分辨率或恢复自动曝光");}
            },main);
        }catch(Exception e){if(useRaw&&device!=null)configure(record,false);else listener.error("配置摄像头失败："+e.getMessage());}
    }
    private void applyExposure(CaptureRequest.Builder request,int iso,long shutter){
        request.set(CaptureRequest.CONTROL_AE_MODE,iso>0?CaptureRequest.CONTROL_AE_MODE_OFF:CaptureRequest.CONTROL_AE_MODE_ON);
        request.set(CaptureRequest.SENSOR_SENSITIVITY,iso>0?iso:null);
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME,iso>0?shutter:null);
        // Frame duration is intentionally left to the HAL for devices with incomplete capability metadata.
    }
    void setTorch(boolean enabled) throws Exception {
        if(!flashAvailable)throw new IllegalStateException("此摄像头没有可用的闪光灯");
        if(session==null||activeRequest==null||activeCallback==null)throw new IllegalStateException("摄像头尚未准备好");
        boolean previous=torchEnabled;
        try{activeRequest.set(CaptureRequest.FLASH_MODE,enabled?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);session.setRepeatingRequest(activeRequest.build(),activeCallback,main);torchEnabled=enabled;}
        catch(Exception error){activeRequest.set(CaptureRequest.FLASH_MODE,previous?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);try{session.setRepeatingRequest(activeRequest.build(),activeCallback,main);}catch(Exception ignored){}throw error;}
    }
    void updateExposure(int iso,long shutter) throws Exception {
        if(session==null||activeRequest==null||activeCallback==null)throw new IllegalStateException("摄像头尚未准备好");
        ExposureOptions.read(characteristics).validate(iso,shutter);
        int previousIso=requestedIso;long previousShutter=requestedShutter;
        try{applyExposure(activeRequest,iso,shutter);session.setRepeatingRequest(activeRequest.build(),activeCallback,main);requestedIso=iso;requestedShutter=shutter;}
        catch(Exception error){applyExposure(activeRequest,previousIso,previousShutter);try{session.setRepeatingRequest(activeRequest.build(),activeCallback,main);}catch(Exception ignored){}throw error;}
    }
    void start(File file) {
        if(device==null || size==null){listener.error("摄像头尚未准备好");return;}
        try {
            recorder=new MediaRecorder(); recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); recorder.setOutputFile(file.getAbsolutePath());
            if(!RecordingOptions.acceptsBitrate(size,recordingBitrate()))throw new IllegalArgumentException("所选码率不受支持，请在设置中重新选择");
            recorder.setVideoEncodingBitRate(recordingBitrate()); recorder.setVideoFrameRate(30);
            recorder.setVideoSize(size.getWidth(),size.getHeight()); recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setOrientationHint(videoRotation); recorder.prepare(); configure(true);
        }catch(Exception e){listener.error("无法开始录像："+e.getMessage());}
    }
    boolean stop() {
        boolean valid=false;
        try { if(session!=null){session.stopRepeating();session.abortCaptures();} }catch(Exception ignored){}
        if(recorder!=null){try {if(recording){recorder.stop();valid=true;}}catch(Exception ignored){}finally{recorder.release();recorder=null;}}
        recording=false; return valid;
    }
    void close() {
        if(torchEnabled&&session!=null&&activeRequest!=null){try{activeRequest.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);session.capture(activeRequest.build(),null,main);}catch(Exception ignored){}}
        torchEnabled=false;++generation;++sessionGeneration;rawActive=false;activeRequest=null;activeCallback=null;
        if(session!=null){session.close();session=null;}
        stop();
        if(device!=null){device.close();device=null;}
        if(preview!=null){preview.release();preview=null;}
        if(reader!=null){reader.close();reader=null;}
    }
}
