package cn.platejournal;

public class FrameClockTest {
    public static void main(String[] args){
        FrameClock clock=new FrameClock();
        require(clock.relativeMs(1234)==-1,"preview before encoder start is not indexed");
        clock.firstFrame(9_000_000_000L);
        require(clock.relativeMs(8_999_999_999L)==-1,"old preview frame is excluded");
        require(clock.relativeMs(9_000_000_000L)==0,"first encoded frame is time zero");
        long capturedMs=clock.relativeMs(10_250_000_000L);
        clock.firstFrame(12_000_000_000L);
        require(capturedMs==1250&&clock.relativeMs(10_250_000_000L)==1250,"inference completion cannot move original capture time");
        clock.reset();clock.firstFrame(30_000_000_000L);
        require(clock.relativeMs(30_050_000_000L)==50,"new recording gets independent origin");
        System.out.println("Live recording timestamp and recording-boundary checks passed");
    }
    private static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
}
