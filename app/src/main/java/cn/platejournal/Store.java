package cn.platejournal;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import com.hyperai.hyperlpr3.bean.Plate;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Store {
    static final class Hit {
        long ms; String code, image; float confidence;
        Hit(long t, String c, String f, float s) { ms=t; code=c; image=f; confidence=s; }
    }
    static final class Clip {
        File dir; long created, duration; long intervalMs=500; int videoWidth,videoHeight,bitrate,iso;long shutterNs;boolean liveIndex; String state="待分析"; ArrayList<Hit> hits=new ArrayList<>();
        File video() { return new File(dir,"video.mp4"); }
    }
    final File root;
    Store(Context c) { root=new File(c.getExternalFilesDir(null),"recordings"); root.mkdirs(); }
    Clip create() throws Exception {
        Clip c=new Clip(); c.created=System.currentTimeMillis(); c.dir=new File(root,UUID.randomUUID().toString());
        if(!c.dir.mkdirs()) throw new IOException("无法建立录像文件夹"); save(c); return c;
    }
    void save(Clip c) throws Exception {
        JSONObject j=new JSONObject(); j.put("created",c.created); j.put("duration",c.duration); j.put("state",c.state); j.put("intervalMs",c.intervalMs); j.put("videoWidth",c.videoWidth); j.put("videoHeight",c.videoHeight);j.put("bitrate",c.bitrate);j.put("iso",c.iso);j.put("shutterNs",c.shutterNs);j.put("liveIndex",c.liveIndex);
        JSONArray a=new JSONArray();
        for(Hit h:c.hits) { JSONObject v=new JSONObject(); v.put("ms",h.ms); v.put("code",h.code); v.put("image",h.image); v.put("confidence",h.confidence); a.put(v); }
        j.put("hits",a); AtomicFile file=new AtomicFile(new File(c.dir,"index.json")); FileOutputStream out=null;
        try { out=file.startWrite(); out.write(j.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch(Exception e) { if(out!=null) file.failWrite(out); throw e; }
    }
    ArrayList<Clip> list() {
        ArrayList<Clip> out=new ArrayList<>(); File[] dirs=root.listFiles(); if(dirs==null)return out;
        for(File d:dirs) try {
            AtomicFile f=new AtomicFile(new File(d,"index.json"));
            JSONObject j=new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8));
            Clip c=new Clip(); c.dir=d; c.created=j.getLong("created"); c.duration=j.optLong("duration"); c.state=j.optString("state","待分析"); c.intervalMs=Math.max(100,j.optLong("intervalMs",500)); c.videoWidth=j.optInt("videoWidth"); c.videoHeight=j.optInt("videoHeight");c.bitrate=j.optInt("bitrate");c.iso=j.optInt("iso");c.shutterNs=j.optLong("shutterNs");c.liveIndex=j.optBoolean("liveIndex",false);
            JSONArray a=j.optJSONArray("hits"); if(a!=null)for(int i=0;i<a.length();i++) { JSONObject h=a.getJSONObject(i); c.hits.add(new Hit(h.getLong("ms"),h.getString("code"),h.getString("image"),(float)h.getDouble("confidence"))); }
            if(c.video().exists()) out.add(c);
        } catch(Exception ignored) { }
        out.sort((a,b)->Long.compare(b.created,a.created)); return out;
    }
    Hit capture(Clip c, long ms, Plate p, Bitmap frame) throws Exception {
        int x=Math.max(0,(int)p.getX1()), y=Math.max(0,(int)p.getY1());
        int right=Math.min(frame.getWidth(),(int)Math.ceil(p.getX2())), bottom=Math.min(frame.getHeight(),(int)Math.ceil(p.getY2()));
        if(right<=x || bottom<=y)return null;
        String name="plate_"+ms+"_"+UUID.randomUUID()+".jpg"; Bitmap crop=Bitmap.createBitmap(frame,x,y,right-x,bottom-y);
        try(FileOutputStream o=new FileOutputStream(new File(c.dir,name))) {
            if(!crop.compress(Bitmap.CompressFormat.JPEG,92,o))throw new IOException("截图保存失败");
        } finally { if(crop!=frame)crop.recycle(); }
        return new Hit(ms,p.getCode(),name,p.getConfidence());
    }
    void delete(Clip c) throws IOException {
        File[] files=c.dir.listFiles(); if(files!=null)for(File f:files)if(!f.delete())throw new IOException("无法删除 "+f.getName());
        if(!c.dir.delete())throw new IOException("无法删除录像文件夹");
    }
}
