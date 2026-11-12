package cn.platejournal;
public class CameraRotationTest {
    public static void main(String[] args){
        int[] back={90,0,270,180},front={270,0,90,180};
        for(int i=0;i<4;i++){
            if(CameraRotation.degrees(90,i*90,false)!=back[i])throw new AssertionError("back "+i);
            if(CameraRotation.degrees(270,i*90,true)!=front[i])throw new AssertionError("front "+i);
        }
        for(int sensor:new int[]{0,90,180,270})for(int display:new int[]{0,90,180,270})for(boolean frontFacing:new boolean[]{false,true}){
            int rotation=CameraRotation.degrees(sensor,display,frontFacing);
            if(rotation<0||rotation>=360||rotation%90!=0)throw new AssertionError("invalid rotation");
        }
        System.out.println("CameraRotationTest passed");
    }
}
