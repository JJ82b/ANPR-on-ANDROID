package cn.platejournal;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class YuvPlanesTest {
    public static void main(String[] args){
        // Padded Y rows and interleaved UV with two separate plane views, as used by phone drivers.
        ByteBuffer y=ByteBuffer.wrap(new byte[]{16,64,99,99,(byte)128,(byte)235,99,99});
        ByteBuffer uv=ByteBuffer.wrap(new byte[]{(byte)128,(byte)128,99,99});
        ByteBuffer u=uv.duplicate(),v=uv.duplicate();v.position(1);
        byte[] packed=YuvPlanes.nv21(2,2,new ByteBuffer[]{y,u,v},new int[]{4,4,4},new int[]{1,2,2},0,0);
        require(Arrays.equals(packed,new byte[]{16,64,(byte)128,(byte)235,(byte)128,(byte)128}),"strides and buffer offsets");
        int[] zero=YuvPlanes.uprightArgb(packed,2,2,0);
        require(zero[0]==0xff000000&&zero[3]==0xffffffff,"YUV black and white levels");
        require(Arrays.equals(YuvPlanes.uprightArgb(packed,2,2,90),new int[]{zero[2],zero[0],zero[3],zero[1]}),"portrait 90 rotation");
        require(Arrays.equals(YuvPlanes.uprightArgb(packed,2,2,180),new int[]{zero[3],zero[2],zero[1],zero[0]}),"reverse landscape 180 rotation");
        require(Arrays.equals(YuvPlanes.uprightArgb(packed,2,2,270),new int[]{zero[1],zero[3],zero[0],zero[2]}),"reverse portrait 270 rotation");
        ByteBuffer croppedY=ByteBuffer.wrap(new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16});
        byte[] crop=YuvPlanes.nv21(2,2,new ByteBuffer[]{croppedY,ByteBuffer.wrap(new byte[]{1,2,3,4}),ByteBuffer.wrap(new byte[]{5,6,7,8})},new int[]{4,2,2},new int[]{1,1,1},2,2);
        require(Arrays.equals(crop,new byte[]{11,12,15,16,8,4}),"crop and planar chroma");
        byte[] red={82,82,82,82,(byte)240,90};
        int color=YuvPlanes.uprightArgb(red,2,2,0)[0];
        require(((color>>16)&255)>240&&((color>>8)&255)<15&&(color&255)<15,"NV21 chroma ordering preserves red");
        System.out.println("Raw frame stride, crop, color and all four orientations passed");
    }
    private static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
}
