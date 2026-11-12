package cn.platejournal;
final class CameraRotation {
    static int degrees(int sensor,int display,boolean front){return (sensor+(front?display:-display)+360)%360;}
}
