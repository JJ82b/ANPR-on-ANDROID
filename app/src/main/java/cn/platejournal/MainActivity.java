package cn.platejournal;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.util.Size;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.hyperai.hyperlpr3.HyperLPR3;
import com.hyperai.hyperlpr3.bean.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class MainActivity extends Activity {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private long sampleMs=500; private int videoWidth=1280,videoHeight=720;
    private int chosenBitrate=0,chosenIso=0;private long chosenShutter=0,lastFrameAt=0;private Bitmap lastInferenceFrame;
    private int previewContentWidth,previewContentHeight; private boolean previewHasFrame=false; private boolean resumePlayback=false, pendingRelayout=false;
    private Store store; private LinearLayout root; private TextView status,clock,nearby;
    private boolean desiredTorch=false;
    private String selectedCameraId="",selectedCameraLabel="摄像头";
    private Button cameraButton;
    private Button resolutionButton,isoButton,shutterButton,torchButton;
    private Button recordButton; private TextureView texture; private PlateOverlay overlay;
    private FrameLayout previewBox; private CameraEngine camera; private VideoView player;
    private SeekBar seek; private LinearLayout hitsBox;
    private Store.Clip active,exportClip; private long recordStart; private int screen=0,epoch=0;
    private volatile boolean sdkReady=false, destroyed=false, analyzing=false,cancelAnalysis=false;
    private boolean finalizing=false;
    private boolean foreground=false,recording=false,starting=false,liveBusy=false,dragging=false;
    private int lastBucket=-1; private long pendingSeek=0;
    private ImageView currentPlateImage; private String currentImagePath="";
    private static final int BG=0xff101923, PANEL=0xff1c2b39, GREEN=0xff41e5aa, WHITE=0xffedf5fa, MUTED=0xffadbfcc;

    public void onCreate(Bundle b){
        super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);store=new Store(this);loadSettings();showLibrary();
        WORK.execute(()->{try{
            HyperLPR3.getInstance().init(getApplicationContext(),new HyperLPRParameter().setDetLevel(HyperLPR3.DETECT_LEVEL_HIGH).setMaxNum(8).setThreads(2).setRecConfidenceThreshold(.85f));
            sdkReady=true;post(()->{if(screen==0)status.setText("离线识别已就绪 · 视频和截图仅存本机");});
        }catch(Throwable e){post(()->message("识别引擎加载失败",e.toString()));}});
    }
    private void post(Runnable r){ui.post(()->{if(!destroyed)r.run();});}
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(dp(4),dp(5),dp(4),dp(5));return v;}
    private Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setTextColor(WHITE);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void base(String title,String subtitle){
        ++epoch;root=column();root.setBackgroundColor(BG);root.setPadding(dp(14),dp(8),dp(14),dp(8));setContentView(root);
        root.addView(text(title,25,WHITE));status=text(subtitle,12,MUTED);root.addView(status);
    }
    private void message(String title,String body){if(!isFinishing())new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("知道了",null).show();}
    private String date(long t){return new SimpleDateFormat("MM月dd日 HH:mm:ss",Locale.CHINA).format(new Date(t));}
    private void showLibrary(){
        if(finalizing)return;
        if(analyzing||(screen==2&&liveBusy))return;ui.removeCallbacks(liveTick);closeCamera();desiredTorch=false;releasePlayer();screen=0;active=null;
        base("车牌录像",sdkReady?"离线识别 · 录像、车牌截图和时间线":"正在加载离线识别模型…");
        root.addView(button("设置 · 录像与曝光",this::showSettings));
        root.addView(button("＋ 开始拍摄",()->{if(!sdkReady){message("稍等一下","识别模型尚未准备好。");return;}showCamera();}));
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ArrayList<Store.Clip> clips=store.list();
        if(clips.isEmpty())list.addView(text("还没有录像\n\n开始拍摄后，绿色方框会标出车牌。录制中保存车牌时间点，停止后直接查看，支持回放和导出。",16,MUTED));
        for(Store.Clip c:clips){
            LinearLayout card=column();card.setPadding(dp(10),dp(8),dp(10),dp(8));card.setBackgroundColor(PANEL);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(10),0,0);list.addView(card,cp);
            card.addView(text(date(c.created),18,WHITE));
            Set<String> unique=new HashSet<>();for(Store.Hit h:c.hits)unique.add(h.code);
            card.addView(text(Timeline.time(c.duration)+" · "+unique.size()+" 个车牌 · "+c.state,13,MUTED));
            card.addView(button("回放与车牌时间线",()->showPlayback(c)));
        }
        root.addView(text("默认无声录制 · 请在退出或锁屏前停止录像",11,MUTED));
    }
    private void showCamera(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},10);return;}
        try{ensureResolution();}catch(Exception e){message("无法读取录像能力",e.getMessage());return;}
        ui.removeCallbacks(liveTick);closeCamera();previewContentWidth=0;previewContentHeight=0;previewHasFrame=false;lastFrameAt=0;lastInferenceFrame=null;
        screen=1;base("拍摄与抓牌",resolutionLabel()+" · 抽样 "+seconds(sampleMs)+" 秒 · 可横屏拍摄");
        previewBox=new FrameLayout(this);previewBox.setBackgroundColor(0xff000000);root.addView(previewBox,new LinearLayout.LayoutParams(-1,0,1));
        texture=new TextureView(this);overlay=new PlateOverlay(this);overlay.setElevation(dp(2));
        previewBox.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fitPreview());
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER);previewBox.addView(texture,p);previewBox.addView(overlay,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        clock=text("00:00.0",24,GREEN);root.addView(clock);
        LinearLayout quick=new LinearLayout(this);
        resolutionButton=button("分辨率",this::quickResolution);isoButton=button("ISO",()->quickExposure(true));shutterButton=button("快门",()->quickExposure(false));
        for(Button control:new Button[]{resolutionButton,isoButton,shutterButton}){control.setTextSize(12);quick.addView(control,new LinearLayout.LayoutParams(0,-2,1));}
        root.addView(quick);cameraButton=button(selectedCameraLabel,this::quickCamera);root.addView(cameraButton);torchButton=button("补光灯：关",this::toggleTorch);root.addView(torchButton);refreshQuickButtons();
        recordButton=button("开始录制",()->{if(recording)finishRecording(true);else if(!starting)startRecording();});recordButton.setEnabled(false);root.addView(recordButton);
        root.addView(button("查看识别画面",()->{
            if(lastInferenceFrame==null){message("尚无画面","等待一次识别完成后再查看。");return;}
            ImageView view=new ImageView(this);view.setAdjustViewBounds(true);view.setImageBitmap(lastInferenceFrame);
            new AlertDialog.Builder(this).setTitle("实际送入识别器的画面").setView(view).setPositiveButton("关闭",null).show();
        }));
        root.addView(button("返回录像库",()->{if(recording||starting)confirmStop();else showLibrary();}));
        final TextureView attachedTexture=texture;
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){if(foreground&&texture==attachedTexture)openCamera();}
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){if(camera!=null&&texture==attachedTexture)camera.transform();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s){if(texture==attachedTexture)closeCamera();return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture s){if(texture==attachedTexture)previewHasFrame=true;}
        });
        landscapePanels();ui.post(liveTick);
    }
    private void openCamera(){
        if(camera!=null || texture==null || !texture.isAvailable() || screen!=1)return;
        camera=new CameraEngine(this,texture,selectedCameraId,videoWidth,videoHeight,chosenBitrate,chosenIso,chosenShutter,desiredTorch,new CameraEngine.Listener(){
            public boolean wantsFrame(){return canAnalyze();}
            public void frame(byte[] data,int w,int h,int rotation,long videoMs){analyzeRaw(data,w,h,rotation,videoMs);}
            public void ready(int w,int h){
                if(screen!=1)return;previewContentWidth=w;previewContentHeight=h;fitPreview();
                recordButton.setEnabled(true);refreshQuickButtons();status.setText("实际录像 "+camera.width()+" × "+camera.height()+" · 抽样 "+seconds(sampleMs)+" 秒");
            }
            public void started(){if(screen!=1||!foreground){finishRecording(false);return;}starting=false;recording=true;recordStart=SystemClock.elapsedRealtime();recordButton.setEnabled(true);recordButton.setText("■ 停止并查看车牌");refreshQuickButtons();status.setText("正在录制 · 本段方向已锁定 · 抽样 "+seconds(sampleMs)+" 秒");}
            public void error(String text){boolean had=recording||starting;if(had){finishRecording(false);}else{closeCamera();recordButton.setEnabled(false);}message("摄像头提示",text);}
        });camera.open();
    }
    private final Runnable liveTick=new Runnable(){public void run(){
        if(destroyed||screen!=1)return;
        if(!recording&&!starting&&camera!=null&&camera.rotationChanged()){showCamera();return;}
        if(recording)clock.setText("● "+Timeline.time(SystemClock.elapsedRealtime()-recordStart));
        if(canAnalyze()&&camera!=null&&!camera.rawAnalysis()&&previewHasFrame&&texture!=null&&texture.isAvailable())captureVisibleFrame();
        ui.postDelayed(this,sampleMs);
    }};
    private boolean canAnalyze(){return foreground&&screen==1&&sdkReady&&!liveBusy&&SystemClock.elapsedRealtime()-lastFrameAt>=sampleMs;}
    private void analyzeRaw(byte[] nv21,int w,int h,int rotation,long videoMs){
        if(!canAnalyze())return;liveBusy=true;lastFrameAt=SystemClock.elapsedRealtime();final long started=lastFrameAt;final int token=epoch;final Store.Clip clip=recording&&videoMs>=0?active:null;
        WORK.execute(()->{Bitmap frame=null;
            try{int[] pixels=YuvPlanes.uprightArgb(nv21,w,h,rotation);int ow=rotation%180==0?w:h,oh=rotation%180==0?h:w;frame=Bitmap.createBitmap(pixels,ow,oh,Bitmap.Config.ARGB_8888);processLiveFrame(frame,token,started,clip,videoMs);}
            catch(Throwable e){post(()->{liveBusy=false;if(token==epoch)status.setText("识别画面处理失败："+e.getMessage());});}
            finally{if(frame!=null)frame.recycle();}
        });
    }
    private void captureVisibleFrame(){
        if(texture.getWidth()<=0||texture.getHeight()<=0)return;
        liveBusy=true;lastFrameAt=SystemClock.elapsedRealtime();final long started=lastFrameAt;final int token=epoch;
        final TextureView source=texture;int[] dims=PreviewGeometry.sample(source.getWidth(),source.getHeight());
        final Bitmap frame=Bitmap.createBitmap(dims[0],dims[1],Bitmap.Config.ARGB_8888);
        // Window PixelCopy captures the actually displayed, rotated content, not a TextureView buffer.
        overlay.clear();ui.postDelayed(()->{
            if(token!=epoch||!foreground||screen!=1){frame.recycle();liveBusy=false;return;}
            int[] location=new int[2];source.getLocationInWindow(location);Rect area=new Rect(location[0],location[1],location[0]+source.getWidth(),location[1]+source.getHeight());
            final long videoMs=camera==null?-1:camera.visibleFrameTimeMs();final Store.Clip clip=recording&&videoMs>=0?active:null;
            try{PixelCopy.request(getWindow(),area,frame,result->{
                if(token!=epoch||!foreground||screen!=1){frame.recycle();liveBusy=false;return;}
                if(result!=PixelCopy.SUCCESS){frame.recycle();liveBusy=false;if(token==epoch)status.setText("等待可读取的摄像头画面…");return;}
                WORK.execute(()->{try{processLiveFrame(frame,token,started,clip,videoMs);}finally{frame.recycle();}});
            },ui);}catch(Exception e){frame.recycle();liveBusy=false;if(token==epoch)status.setText("画面读取失败："+e.getMessage());}
        },40);
    }
    private void processLiveFrame(Bitmap frame,int token,long started,Store.Clip clip,long videoMs){
        try{
            Plate[] found=recognize(frame);
            if(clip!=null){for(Plate plate:found){Store.Hit hit=store.capture(clip,videoMs,plate,frame);if(hit!=null)clip.hits.add(hit);}if(found.length>0)store.save(clip);}
            int w=frame.getWidth(),h=frame.getHeight();double scale=Math.min(1.0,480.0/Math.max(w,h));
            Bitmap thumbnail=Bitmap.createScaledBitmap(frame,Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),true);
            if(thumbnail==frame)thumbnail=frame.copy(Bitmap.Config.ARGB_8888,false);final Bitmap snapshot=thumbnail;
            post(()->{liveBusy=false;if(token==epoch&&screen==1&&foreground){lastInferenceFrame=snapshot;overlay.set(found,w,h);String exposure=camera==null?"":camera.exposureStatus();status.setText("识别 "+found.length+" 个 · "+(SystemClock.elapsedRealtime()-started)+" ms · "+exposure);}else snapshot.recycle();});
        }catch(Throwable e){post(()->{liveBusy=false;if(token==epoch)status.setText("识别失败："+e.getMessage());});}
    }
    private Plate[] recognize(Bitmap frame){
        Bitmap input=frame;int longest=Math.max(frame.getWidth(),frame.getHeight());
        if(longest>1920){double scale=1920.0/longest;input=Bitmap.createScaledBitmap(frame,(int)Math.round(frame.getWidth()*scale),(int)Math.round(frame.getHeight()*scale),true);}
        try {
            Plate[] p=HyperLPR3.getInstance().plateRecognition(input,HyperLPR3.CAMERA_ROTATION_0,HyperLPR3.STREAM_BGRA);
            if(p==null)return new Plate[0];
            if(input!=frame){float sx=frame.getWidth()/(float)input.getWidth(),sy=frame.getHeight()/(float)input.getHeight();for(Plate plate:p){plate.setX1(plate.getX1()*sx);plate.setX2(plate.getX2()*sx);plate.setY1(plate.getY1()*sy);plate.setY2(plate.getY2()*sy);}}
            return p;
        }finally{if(input!=frame)input.recycle();}
    }
    private void startRecording(){
        try{if(store.root.getUsableSpace()<100*1024*1024L)throw new IOException("可用空间不足 100 MB，请先导出并删除旧录像。");active=store.create();active.intervalMs=sampleMs;active.videoWidth=camera.width();active.videoHeight=camera.height();active.bitrate=camera.recordingBitrate();active.iso=chosenIso;active.shutterNs=chosenShutter;active.liveIndex=true;active.state="录制中";store.save(active);starting=true;refreshQuickButtons();setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);recordButton.setEnabled(false);camera.start(active.video());}
        catch(Exception e){starting=false;setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);recordButton.setEnabled(true);message("无法录制",e.getMessage());}
    }
    private void finishRecording(boolean openPlayback){
        if((!recording&&!starting)||finalizing)return;
        final Store.Clip c=active;final boolean valid=camera!=null&&camera.stop();recording=false;starting=false;closeCamera();desiredTorch=false;ui.removeCallbacks(liveTick);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        if(c==null){showLibrary();return;}
        finalizing=true;screen=5;base("保存录像","正在写入已识别的车牌记录，不会重新扫描视频…");
        root.addView(new ProgressBar(this));
        // Serial queue drains the last in-flight recognition before finalizing its index.
        WORK.execute(()->{
            String failure=null;
            try{
                if(!valid){store.delete(c);throw new IOException("录像太短或编码器未正常结束，请录制至少两秒。");}
                MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(c.video().getAbsolutePath());c.duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}finally{r.release();}
                c.hits.removeIf(h->h.ms<0||h.ms>=c.duration);c.hits.sort((a,b)->Long.compare(a.ms,b.ms));
                c.state="实时记录完成";store.save(c);cleanUnusedImages(c);
            }catch(Exception e){failure=e.getMessage();if(valid){c.state="实时记录待核对";try{store.save(c);}catch(Exception ignored){}}}
            final String error=failure;post(()->{finalizing=false;liveBusy=false;if(valid&&openPlayback&&foreground)showPlayback(c);else showLibrary();if(error!=null)message("保存提示",error);});
        });
    }
    private void closeCamera(){if(camera!=null){CameraEngine old=camera;camera=null;old.close();}}
    private void confirmStop(){new AlertDialog.Builder(this).setTitle("结束本次录像？").setMessage("保存视频与录制中已抓取的车牌，直接进入回放。").setPositiveButton("结束录制",(d,w)->finishRecording(true)).setNegativeButton("继续录制",null).show();}

    private void analyze(Store.Clip c){
        if(analyzing||(screen==2&&liveBusy))return;ui.removeCallbacks(liveTick);releasePlayer();closeCamera();screen=3;active=c;analyzing=true;cancelAnalysis=false;
        base("生成车牌时间线","正在逐帧抽样分析，所有处理均在本机完成");
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);root.addView(progress);
        TextView detail=text("准备读取视频…",18,WHITE);root.addView(detail);
        root.addView(text("本段每 "+seconds(c.intervalMs)+" 秒抽样一次，保存号码、截图和视频时间。快速经过的车辆可能漏检，可在回放时补查。",14,MUTED));
        Button cancel=button("停止分析并保留已完成结果",()->{cancelAnalysis=true;status.setText("正在保存已完成的结果…");});root.addView(cancel);
        WORK.execute(()->{
            MediaMetadataRetriever r=new MediaMetadataRetriever();String error=null;
            try{
                r.setDataSource(c.video().getAbsolutePath());c.duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                // Keep old screenshots until the new index is atomically committed.
                c.hits=new ArrayList<>();c.state="分析中";store.save(c);
                for(long ms=0;ms<c.duration&&!cancelAnalysis&&!destroyed;ms+=c.intervalMs){
                    Bitmap frame=r.getFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST);
                    if(frame==null)throw new IOException("无法解码 "+Timeline.time(ms)+" 的画面");
                    try{for(Plate p:recognize(frame)){Store.Hit h=store.capture(c,ms,p,frame);if(h!=null)c.hits.add(h);}}finally{frame.recycle();}
                    if(ms%5000<c.intervalMs)store.save(c);
                    final long time=ms;final int count=c.hits.size();post(()->{if(screen==3){progress.setProgress((int)(100*time/Math.max(1,c.duration)));detail.setText(Timeline.time(time)+" / "+Timeline.time(c.duration)+"\n已保存 "+count+" 条车牌采样");}});
                }
                c.state=cancelAnalysis||destroyed?"分析未完成":"分析完成";store.save(c);cleanUnusedImages(c);
            }catch(Throwable e){error=e.getMessage();c.state="分析未完成";try{store.save(c);}catch(Exception ignored){}}
            finally{try{r.release();}catch(Exception ignored){}analyzing=false;}
            final String failure=error;post(()->{showPlayback(c);if(failure!=null)message("分析中断","已保留视频和已有结果，可重新分析。\n"+failure);});
        });
    }
    private void cleanUnusedImages(Store.Clip c){Set<String> used=new HashSet<>();for(Store.Hit h:c.hits)used.add(h.image);File[] all=c.dir.listFiles();if(all!=null)for(File f:all)if(f.getName().startsWith("plate_")&&!used.contains(f.getName()))f.delete();}

    private void showPlayback(Store.Clip c){
        closeCamera();releasePlayer();screen=2;active=c;lastBucket=-1;base("录像回放",date(c.created)+" · "+c.state+" · "+(c.liveIndex?"实时记录":"抽样 "+seconds(c.intervalMs)+" 秒"));
        player=new VideoView(this);FrameLayout box=new FrameLayout(this);box.setBackgroundColor(0xff000000);box.addView(player,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        root.addView(box,new LinearLayout.LayoutParams(-1,dp(230)));
        clock=text("00:00.0 / "+Timeline.time(c.duration),14,WHITE);root.addView(clock);
        seek=new SeekBar(this);seek.setMax((int)Math.min(Integer.MAX_VALUE,c.duration));root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){dragging=true;player.pause();}
            public void onStopTrackingTouch(SeekBar s){dragging=false;player.seekTo(s.getProgress());updateNearby(s.getProgress());}
            public void onProgressChanged(SeekBar s,int p,boolean user){if(user){clock.setText(Timeline.time(p)+" / "+Timeline.time(c.duration));updateNearby(p);}}
        });
        LinearLayout controls=new LinearLayout(this);root.addView(controls);
        controls.addView(button("播放 / 暂停",()->{if(player.isPlaying())player.pause();else player.start();}),new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button("识别此时间点",this::recognizeCurrent),new LinearLayout.LayoutParams(0,-2,1));
        nearby=text("当前时间附近的车牌：—",14,GREEN);root.addView(nearby);
        currentPlateImage=new ImageView(this);currentPlateImage.setAdjustViewBounds(true);currentPlateImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(currentPlateImage,new LinearLayout.LayoutParams(-1,dp(46)));currentImagePath="";
        ScrollView list=new ScrollView(this);hitsBox=column();list.addView(hitsBox);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));renderEvents(c);
        LinearLayout actions=new LinearLayout(this);root.addView(actions);
        actions.addView(button("录像库",this::showLibrary),new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(button("更多",()->new AlertDialog.Builder(this).setItems(new String[]{"手动补扫整个视频","导出视频、截图和 CSV","删除本段录像"},(d,w)->{
            if(liveBusy){message("正在识别","请等待本次识别完成。");return;}
            if(w==0){if(sdkReady)new AlertDialog.Builder(this).setTitle("手动补扫").setMessage("此操作会替换现有车牌索引，使用当前设置的 "+seconds(sampleMs)+" 秒间隔重建本段车牌时间线？").setPositiveButton("开始",(dd,ww)->{c.intervalMs=sampleMs;c.liveIndex=false;analyze(c);}).setNegativeButton("取消",null).show();else message("稍等一下","模型正在加载。");}
            if(w==1)export(c);
            if(w==2)new AlertDialog.Builder(this).setTitle("删除本段录像？").setMessage("视频、截图和车牌记录都会删除。建议先导出。 ").setPositiveButton("删除",(dd,ww)->{try{releasePlayer();store.delete(c);showLibrary();}catch(Exception e){message("删除失败",e.getMessage());}}).setNegativeButton("取消",null).show();
        }).show()),new LinearLayout.LayoutParams(0,-2,1));
        player.setOnPreparedListener(m->{if(screen!=2||active!=c)return;seek.setMax(m.getDuration());if(pendingSeek>0){player.seekTo((int)pendingSeek);pendingSeek=0;}else player.seekTo(1);if(resumePlayback){player.start();resumePlayback=false;}});
        player.setOnErrorListener((m,a,b)->{message("无法回放","视频可能未正常结束或设备不支持其编码。可导出文件检查。 ");return true;});
        player.setVideoPath(c.video().getAbsolutePath());landscapePanels();ui.post(playTick);
    }
    private final Runnable playTick=new Runnable(){public void run(){if(destroyed||screen!=2||player==null)return;if(!dragging&&!liveBusy){int t=player.getCurrentPosition();seek.setProgress(t);clock.setText(Timeline.time(t)+" / "+Timeline.time(active.duration));updateNearby(t);}ui.postDelayed(this,150);}};
    private void updateNearby(long t){
        if(liveBusy)return;
        Store.Hit closest=null;
        LinkedHashSet<String> names=new LinkedHashSet<>();for(Store.Hit h:active.hits)if(Timeline.near(h.ms,t,active.intervalMs)){names.add(h.code);if(closest==null||Math.abs(h.ms-t)<Math.abs(closest.ms-t))closest=h;}
        nearby.setText(names.isEmpty()?"此时间点附近暂无车牌记录":"附近 ±"+seconds(active.intervalMs/2)+" 秒："+String.join("  ·  ",names));
        String path=closest==null?"":new File(active.dir,closest.image).getAbsolutePath();
        if(!path.equals(currentImagePath)){currentImagePath=path;currentPlateImage.setImageBitmap(path.isEmpty()?null:BitmapFactory.decodeFile(path));}
    }
    private void renderEvents(Store.Clip c){
        hitsBox.removeAllViews();hitsBox.addView(text("车牌时间线 · 点击记录跳转，长按查看截图",12,MUTED));
        if(c.hits.isEmpty()){hitsBox.addView(text("暂无车牌。可在“更多”中分析视频，或识别当前时间点。",14,WHITE));return;}
        // Group only consecutive sightings; retain every sample in the stored index.
        ArrayList<Store.Hit> starts=new ArrayList<>();Map<String,Long> last=new HashMap<>();
        for(Store.Hit h:c.hits){Long previous=last.get(h.code);if(previous==null||h.ms-previous>Math.max(1000,c.intervalMs*2))starts.add(h);last.put(h.code,h.ms);}
        for(Store.Hit h:starts){
            TextView row=text(Timeline.time(h.ms)+"   "+h.code+"   "+Math.round(h.confidence*100)+"%",17,WHITE);row.setPadding(dp(10),dp(12),dp(10),dp(12));row.setBackgroundColor(PANEL);
            row.setOnClickListener(v->{player.pause();player.seekTo((int)h.ms);seek.setProgress((int)h.ms);lastBucket=-1;updateNearby(h.ms);});
            row.setOnLongClickListener(v->{ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setImageBitmap(BitmapFactory.decodeFile(new File(c.dir,h.image).getAbsolutePath()));new AlertDialog.Builder(this).setTitle(h.code+" · "+Timeline.time(h.ms)).setView(image).setPositiveButton("关闭",null).show();return true;});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,0);hitsBox.addView(row,lp);
        }
    }
    private void recognizeCurrent(){
        if(!sdkReady||liveBusy)return;player.pause();final long time=player.getCurrentPosition();final Store.Clip c=active;final int token=epoch;
        liveBusy=true;status.setText("正在识别 "+Timeline.time(time)+" 附近的视频帧…");
        WORK.execute(()->{MediaMetadataRetriever r=new MediaMetadataRetriever();String error=null;int n=0;
            try{r.setDataSource(c.video().getAbsolutePath());Bitmap frame=r.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST);if(frame==null)throw new IOException("无法读取此帧");
                try{for(Plate p:recognize(frame)){Store.Hit h=store.capture(c,time,p,frame);if(h!=null){c.hits.add(h);n++;}}}finally{frame.recycle();}
                c.hits.sort((a,b)->Long.compare(a.ms,b.ms));store.save(c);
            }catch(Throwable e){error=e.getMessage();}finally{try{r.release();}catch(Exception ignored){}}
            final String err=error;final int count=n;post(()->{liveBusy=false;if(epoch==token){status.setText(err==null?"本次识别到 "+count+" 个车牌 · "+Timeline.time(time):"识别失败："+err);lastBucket=-1;updateNearby(time);renderEvents(c);if(pendingRelayout){pendingRelayout=false;onConfigurationChanged(getResources().getConfiguration());}}});
        });
    }
    private void releasePlayer(){ui.removeCallbacks(playTick);if(player!=null){player.stopPlayback();player=null;}}
    private void export(Store.Clip c){exportClip=c;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,"车牌录像_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(new Date(c.created))+".zip");startActivityForResult(i,20);}
    protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req!=20||result!=RESULT_OK||data==null||exportClip==null)return;
        Uri uri=data.getData();Store.Clip c=exportClip;status.setText("正在导出，请稍候…");
        WORK.execute(()->{String error=null;try(OutputStream output=getContentResolver().openOutputStream(uri);ZipOutputStream zip=new ZipOutputStream(output)){
            File[] files=c.dir.listFiles();if(files==null)throw new IOException("录像文件夹不可读");byte[] buf=new byte[65536];for(File f:files)if(f.isFile()&&!f.getName().endsWith(".bak")){zip.putNextEntry(new ZipEntry(f.getName()));try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(buf))!=-1)zip.write(buf,0,n);}zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("plates.csv"));zip.write(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});zip.write("视频时间毫秒,车牌号,置信度,截图文件\r\n".getBytes("UTF-8"));
            for(Store.Hit h:c.hits)zip.write((h.ms+",\""+h.code.replace("\"","\"\"")+"\","+h.confidence+","+h.image+"\r\n").getBytes("UTF-8"));zip.closeEntry();
        }catch(Exception e){error=e.getMessage();}final String err=error;post(()->message(err==null?"导出完成":"导出失败",err==null?"已保存视频、车牌截图、时间索引和 CSV 表格。":err));});
    }
    private String seconds(long ms){return String.format(Locale.ROOT,"%.2f",ms/1000.0).replaceAll("0+$", "").replaceAll("\\.$", "");}
    private void refreshQuickButtons(){
        if(screen!=1||resolutionButton==null)return;
        if(cameraButton!=null){cameraButton.setText("镜头："+selectedCameraLabel);cameraButton.setEnabled(!recording&&!starting);}
        resolutionButton.setText("分辨率\n"+videoWidth+"×"+videoHeight);resolutionButton.setEnabled(!recording&&!starting);
        isoButton.setText("ISO\n"+(chosenIso==0?"自动":chosenIso));shutterButton.setText("快门\n"+ExposureOptions.shutter(chosenShutter));
        isoButton.setEnabled(!starting);shutterButton.setEnabled(!starting);
        if(torchButton!=null){boolean available=camera!=null&&camera.hasFlash();torchButton.setText(camera==null?"补光灯：等待摄像头":!available?"补光灯：此摄像头不支持":camera.torchEnabled()?"补光灯：开 · 点击关闭":"补光灯：关 · 点击开启");torchButton.setEnabled(available&&!starting);torchButton.setTextColor(available&&camera.torchEnabled()?GREEN:WHITE);}
    }
    private void toggleTorch(){
        if(camera==null||starting)return;
        try{camera.setTorch(!camera.torchEnabled());desiredTorch=camera.torchEnabled();refreshQuickButtons();}
        catch(Exception e){message("补光灯未切换",e.getMessage());refreshQuickButtons();}
    }
    private void quickCamera(){
        if(recording||starting)return;
        try{
            ArrayList<CameraOptions.Entry> entries=CameraOptions.list(this);
            String[] labels=new String[entries.size()];int current=-1;
            for(int i=0;i<entries.size();i++){labels[i]=entries.get(i).label;if(entries.get(i).id.equals(selectedCameraId))current=i;}
            new AlertDialog.Builder(this).setTitle("选择镜头（系统开放的摄像头）").setSingleChoiceItems(labels,current,(dialog,which)->{
                String previous=selectedCameraId;int oldW=videoWidth,oldH=videoHeight,oldRate=chosenBitrate,oldIso=chosenIso;long oldShutter=chosenShutter;
                try{
                    selectedCameraId=entries.get(which).id;ensureResolution();
                    desiredTorch=false;
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putString("cameraId",selectedCameraId).putInt("videoWidth",videoWidth).putInt("videoHeight",videoHeight).putInt("bitrate",chosenBitrate).putInt("iso",chosenIso).putLong("shutterNs",chosenShutter).apply();
                    dialog.dismiss();showCamera();
                }catch(Exception e){selectedCameraId=previous;videoWidth=oldW;videoHeight=oldH;chosenBitrate=oldRate;chosenIso=oldIso;chosenShutter=oldShutter;try{ensureResolution();}catch(Exception ignored){}message("此镜头无法录像",e.getMessage());}
            }).setNegativeButton("取消",null).show();
        }catch(Exception e){message("无法读取摄像头",e.getMessage());}
    }
    private void quickResolution(){
        if(recording||starting)return;
        try{
            ArrayList<Size> options=ensureResolution();String[] labels=new String[options.size()];for(int i=0;i<labels.length;i++)labels[i]=RecordingOptions.label(options.get(i));
            new AlertDialog.Builder(this).setTitle("录像分辨率").setSingleChoiceItems(labels,options.indexOf(new Size(videoWidth,videoHeight)),(dialog,which)->{
                Size selected=options.get(which);videoWidth=selected.getWidth();videoHeight=selected.getHeight();
                if(chosenBitrate!=0&&!RecordingOptions.acceptsBitrate(selected,chosenBitrate))chosenBitrate=0;
                getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("videoWidth",videoWidth).putInt("videoHeight",videoHeight).putInt("bitrate",chosenBitrate).apply();
                dialog.dismiss();showCamera();
            }).setNegativeButton("取消",null).show();
        }catch(Exception e){message("无法选择分辨率",e.getMessage());}
    }
    private void quickExposure(boolean chooseIso){
        if(starting)return;
        try{
            ExposureOptions options=ExposureOptions.read(this,selectedCameraId);
            if(!options.supported){message("未提供可用范围","此摄像头没有提供可用的 ISO 或曝光时间范围。");return;}
            ArrayList<String> labels=new ArrayList<>();labels.add("恢复自动曝光（ISO 和快门均自动）");int current=0;
            if(chooseIso){for(int value:options.isos)labels.add("ISO "+value);if(chosenIso>0)current=options.isos.indexOf(chosenIso)+1;}
            else {for(long value:options.shutters)labels.add(ExposureOptions.shutter(value));if(chosenShutter>0)current=options.shutters.indexOf(chosenShutter)+1;}
            new AlertDialog.Builder(this).setTitle(chooseIso?"ISO · 手动时快门也会固定":"快门 · 手动时 ISO 也会固定").setSingleChoiceItems(labels.toArray(new String[0]),Math.max(0,current),(dialog,which)->{
                try{
                    if(camera==null&&which==0&&!recording){chosenIso=0;chosenShutter=0;getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("iso",0).putLong("shutterNs",0).apply();dialog.dismiss();showCamera();return;}
                    if(camera==null)throw new IllegalStateException("摄像头尚未准备好");int iso=0;long shutter=0;
                    if(which>0){
                        long desiredIso=chosenIso>0?chosenIso:camera.actualIso()>0?camera.actualIso():100;
                        long desiredTime=chosenShutter>0?chosenShutter:camera.actualShutter()>0?camera.actualShutter():1_000_000_000L/60;
                        iso=options.isos.get(0);for(int candidate:options.isos)if(Math.abs(candidate-desiredIso)<Math.abs(iso-desiredIso))iso=candidate;
                        shutter=options.shutters.get(0);for(long candidate:options.shutters)if(Math.abs(candidate-desiredTime)<Math.abs(shutter-desiredTime))shutter=candidate;
                        if(chooseIso)iso=options.isos.get(which-1);else shutter=options.shutters.get(which-1);
                    }
                    camera.updateExposure(iso,shutter);chosenIso=iso;chosenShutter=shutter;
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("iso",iso).putLong("shutterNs",shutter).apply();refreshQuickButtons();
                    status.setText("已提交曝光设置，顶部随后显示相机实际值");dialog.dismiss();
                }catch(Exception e){message("曝光设置未应用",e.getMessage());}
            }).setNegativeButton("取消",null).show();
        }catch(Exception e){message("无法读取曝光范围",e.getMessage());}
    }
    private void fitPreview(){
        if(screen!=1||previewBox==null)return;
        int[] dims=PreviewGeometry.fit(previewBox.getWidth(),previewBox.getHeight(),previewContentWidth,previewContentHeight);
        if(dims[0]==0||dims[1]==0)return;
        for(View v:new View[]{texture,overlay}){ViewGroup.LayoutParams old=v.getLayoutParams();if(old.width!=dims[0]||old.height!=dims[1])v.setLayoutParams(new FrameLayout.LayoutParams(dims[0],dims[1],Gravity.CENTER));}
        overlay.bringToFront();overlay.invalidate();texture.post(()->{if(camera!=null)camera.transform();});
    }
    private String resolutionLabel(){return RecordingOptions.label(new Size(videoWidth,videoHeight));}
    private void loadSettings(){SharedPreferences p=getSharedPreferences("settings",MODE_PRIVATE);selectedCameraId=p.getString("cameraId","");sampleMs=p.getLong("sampleMs",500);int old=Math.max(0,Math.min(2,p.getInt("resolution",1)));videoWidth=p.getInt("videoWidth",new int[]{640,1280,1920}[old]);videoHeight=p.getInt("videoHeight",new int[]{480,720,1080}[old]);chosenBitrate=p.getInt("bitrate",0);chosenIso=p.getInt("iso",0);chosenShutter=p.getLong("shutterNs",0);}
    private ArrayList<Size> ensureResolution() throws Exception {
        ArrayList<CameraOptions.Entry> cameras=CameraOptions.list(this);
        if(cameras.isEmpty())throw new IllegalStateException("没有可用的摄像头");
        CameraOptions.Entry selected=null;
        for(CameraOptions.Entry entry:cameras)if(entry.id.equals(selectedCameraId))selected=entry;
        if(selected==null){selected=cameras.get(0);for(CameraOptions.Entry entry:cameras)if(entry.rear){selected=entry;break;}}
        selectedCameraId=selected.id;selectedCameraLabel=selected.label;

        ArrayList<Size> options=RecordingOptions.supported(this,selectedCameraId);if(options.isEmpty())throw new IllegalStateException("未找到支持 H.264 / 30fps 的录像尺寸");
        if(!options.contains(new Size(videoWidth,videoHeight))){Size fallback=options.get(options.size()-1);for(Size option:options)if(option.getWidth()<=videoWidth&&option.getHeight()<=videoHeight){fallback=option;break;}videoWidth=fallback.getWidth();videoHeight=fallback.getHeight();}
        if(chosenBitrate!=0&&!RecordingOptions.acceptsBitrate(new Size(videoWidth,videoHeight),chosenBitrate))chosenBitrate=0;
        try{ExposureOptions.read(this,selectedCameraId).validate(chosenIso,chosenShutter);}catch(IllegalArgumentException e){chosenIso=0;chosenShutter=0;}
        return options;
    }
    private void showSettings(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},11);return;}
        final ArrayList<Size> available;final ExposureOptions exposure;
        try{available=ensureResolution();exposure=ExposureOptions.read(this,selectedCameraId);}catch(Exception e){message("无法读取录像能力",e.getMessage());return;}
        screen=4;base("设置","保存后用于新录像；旧录像可选择重新分析");
        ScrollView sc=new ScrollView(this);LinearLayout body=column();sc.addView(body);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(text("车牌抽样间隔",18,WHITE));
        final long[] intervals={100,250,500,1000,2000,5000};
        Spinner interval=new Spinner(this);String[] labels={"0.1 秒 · 更密集","0.25 秒","0.5 秒 · 默认","1 秒","2 秒","5 秒 · 更省时"};
        ArrayAdapter<String> ia=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);interval.setAdapter(ia);for(int i=0;i<intervals.length;i++)if(intervals[i]==sampleMs)interval.setSelection(i);body.addView(interval);
        body.addView(text("控制实时识别的最短间隔，录制时直接保存车牌时间点和截图。实际识别频率受手机性能限制；越密集越耗电、截图越多。",13,MUTED));
        body.addView(text("录像分辨率",18,WHITE));Spinner quality=new Spinner(this);
        ArrayList<String> names=new ArrayList<>();for(Size size:available)names.add(RecordingOptions.label(size));
        quality.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));quality.setSelection(available.indexOf(new Size(videoWidth,videoHeight)));body.addView(quality);
        body.addView(text("仅列出当前所选摄像头及 H.264 编码器共同支持的 30fps 尺寸，支持 4K 时自动显示。实际组合还需录制测试；4K 更占空间、分析更慢。",13,MUTED));

        body.addView(text("视频码率",18,WHITE));Spinner bitrateChoice=new Spinner(this);body.addView(bitrateChoice);
        final ArrayList<Integer> rates=new ArrayList<>();final int[] preferredRate={chosenBitrate};
        Runnable updateRates=()->{rates.clear();rates.addAll(RecordingOptions.bitrates(available.get(quality.getSelectedItemPosition())));ArrayList<String> labelsRate=new ArrayList<>();for(int value:rates)labelsRate.add(value==0?"自动 · 根据分辨率":""+String.format(Locale.ROOT,"%.1f",value/1_000_000.0)+" Mbps");bitrateChoice.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labelsRate));bitrateChoice.setSelection(Math.max(0,rates.indexOf(preferredRate[0])));};
        quality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){updateRates.run();}});updateRates.run();
        bitrateChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(pos<rates.size())preferredRate[0]=rates.get(pos);}});
        body.addView(text("码率控制文件大小和压缩质量；列表随分辨率及编码器能力更新。",13,MUTED));
        body.addView(text("曝光模式",18,WHITE));Spinner exposureMode=new Spinner(this);
        exposureMode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,exposure.supported?new String[]{"自动曝光","手动 ISO + 快门"}:new String[]{"自动曝光 · 本机未提供手动范围"}));
        exposureMode.setSelection(exposure.supported&&chosenIso>0?1:0);body.addView(exposureMode);
        body.addView(text("ISO 感光度",16,WHITE));Spinner isoChoice=new Spinner(this);ArrayList<String> isoLabels=new ArrayList<>();for(int value:exposure.isos)isoLabels.add("ISO "+value);if(isoLabels.isEmpty())isoLabels.add("不支持手动 ISO");isoChoice.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,isoLabels));isoChoice.setSelection(Math.max(0,exposure.isos.indexOf(chosenIso>0?chosenIso:100)));body.addView(isoChoice);
        body.addView(text("快门速度",16,WHITE));Spinner shutterChoice=new Spinner(this);ArrayList<String> shutterLabels=new ArrayList<>();for(long value:exposure.shutters)shutterLabels.add(ExposureOptions.shutter(value));if(shutterLabels.isEmpty())shutterLabels.add("不支持手动快门");shutterChoice.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,shutterLabels));shutterChoice.setSelection(Math.max(0,exposure.shutters.indexOf(chosenShutter>0?chosenShutter:1_000_000_000L/60)));body.addView(shutterChoice);
        Runnable enableExposure=()->{boolean manual=exposure.supported&&exposureMode.getSelectedItemPosition()==1;isoChoice.setEnabled(manual);shutterChoice.setEnabled(manual);};enableExposure.run();
        exposureMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){enableExposure.run();}});
        body.addView(text("手动模式需同时设置 ISO 和快门。采用兼容模式尝试控制，是否生效以拍摄页实际值为准。当前录像为 30fps，快门不慢于约 1/30 秒；较快快门可减少运动模糊，但画面会变暗。拍摄页显示相机实际报告的曝光值。",13,MUTED));

        body.addView(text("横屏与竖屏",18,WHITE));body.addView(text("拍摄前旋转手机即可切换。开始录制后锁定本段方向，停止后恢复旋转。回放支持横屏左右分栏。",13,MUTED));
        body.addView(button("保存设置",()->{sampleMs=intervals[interval.getSelectedItemPosition()];Size selected=available.get(quality.getSelectedItemPosition());videoWidth=selected.getWidth();videoHeight=selected.getHeight();chosenBitrate=rates.get(bitrateChoice.getSelectedItemPosition());boolean manual=exposure.supported&&exposureMode.getSelectedItemPosition()==1;chosenIso=manual?exposure.isos.get(isoChoice.getSelectedItemPosition()):0;chosenShutter=manual?exposure.shutters.get(shutterChoice.getSelectedItemPosition()):0;getSharedPreferences("settings",MODE_PRIVATE).edit().putLong("sampleMs",sampleMs).putInt("videoWidth",videoWidth).putInt("videoHeight",videoHeight).putInt("bitrate",chosenBitrate).putInt("iso",chosenIso).putLong("shutterNs",chosenShutter).apply();showLibrary();}));
        body.addView(button("取消",this::showLibrary));
    }
    private void landscapePanels(){
        if(getResources().getConfiguration().orientation!=Configuration.ORIENTATION_LANDSCAPE)return;
        View visual=root.getChildAt(2);root.removeView(visual);LinearLayout side=column();
        while(root.getChildCount()>2){View child=root.getChildAt(2);root.removeView(child);side.addView(child);}
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(visual,new LinearLayout.LayoutParams(0,-1,1.2f));
        if(screen==1){ScrollView controls=new ScrollView(this);controls.setFillViewport(true);controls.addView(side);row.addView(controls,new LinearLayout.LayoutParams(0,-1,1f));}
        else row.addView(side,new LinearLayout.LayoutParams(0,-1,1f));
        root.addView(row,new LinearLayout.LayoutParams(-1,0,1));
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        if(recording||starting)return;
        if(screen==1){showCamera();}
        else if(screen==2&&!liveBusy){pendingSeek=player==null?0:player.getCurrentPosition();resumePlayback=player!=null&&player.isPlaying();showPlayback(active);}
        else if(screen==2){pendingRelayout=true;}
        else if(screen==0){showLibrary();}
        // Settings controls and analysis progress retain their existing state on rotation.
    }
    public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==10||r==11){if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED){if(r==11)showSettings();else showCamera();}else message("需要摄像头权限","允许摄像头权限后才能录制。可在系统应用设置中开启。 ");}}
    protected void onResume(){super.onResume();foreground=true;if(screen==1){openCamera();ui.removeCallbacks(liveTick);ui.post(liveTick);}}
    protected void onPause(){foreground=false;desiredTorch=false;if(recording||starting)finishRecording(false);closeCamera();if(player!=null)player.pause();super.onPause();}
    protected void onDestroy(){destroyed=true;cancelAnalysis=true;ui.removeCallbacksAndMessages(null);closeCamera();releasePlayer();super.onDestroy();}
    public void onBackPressed(){if(finalizing)return;if(analyzing){cancelAnalysis=true;status.setText("正在保存已完成的结果…");}else if(recording||starting)confirmStop();else if(screen!=0)showLibrary();else super.onBackPressed();}
}
