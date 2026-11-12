package cn.platejournal;

import java.nio.ByteBuffer;

/** Packs YUV_420_888 planes without assuming row padding or interleaved chroma. */
final class YuvPlanes {
    static int[] uprightArgb(byte[] nv21,int width,int height,int rotation){
        if(rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270)throw new IllegalArgumentException("Invalid rotation");
        int[] pixels=new int[width*height];int outputWidth=rotation%180==0?width:height;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int luminance=Math.max(0,(nv21[y*width+x]&255)-16);
            int chroma=width*height+(y/2)*width+(x&~1);
            int v=(nv21[chroma]&255)-128,u=(nv21[chroma+1]&255)-128;
            int r=clamp((298*luminance+409*v+128)>>8),g=clamp((298*luminance-100*u-208*v+128)>>8),b=clamp((298*luminance+516*u+128)>>8);
            int ox=x,oy=y;
            if(rotation==90){ox=height-1-y;oy=x;}else if(rotation==180){ox=width-1-x;oy=height-1-y;}else if(rotation==270){ox=y;oy=width-1-x;}
            pixels[oy*outputWidth+ox]=0xff000000|(r<<16)|(g<<8)|b;
        }
        return pixels;
    }
    private static int clamp(int value){return Math.max(0,Math.min(255,value));}
    static byte[] nv21(int width,int height,ByteBuffer[] planes,int[] rows,int[] pixels,int left,int top){
        if(width<=0||height<=0||(width&1)!=0||(height&1)!=0||(left&1)!=0||(top&1)!=0)throw new IllegalArgumentException("YUV crop must be even");
        byte[] out=new byte[width*height*3/2];
        for(int plane=0;plane<3;plane++){
            int shift=plane==0?0:1,w=width>>shift,h=height>>shift;
            ByteBuffer buffer=planes[plane].duplicate();int base=buffer.position()+(top>>shift)*rows[plane]+(left>>shift)*pixels[plane];
            int offset=plane==0?0:width*height+(plane==1?1:0),step=plane==0?1:2;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)out[offset+(y*w+x)*step]=buffer.get(base+y*rows[plane]+x*pixels[plane]);
        }
        return out;
    }
}
