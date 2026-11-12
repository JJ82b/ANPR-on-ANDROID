package cn.platejournal;
public class TimelineTest {
    public static void main(String[] args) {
        require(Timeline.near(1000,750),"left boundary");
        require(Timeline.near(1000,1250),"right boundary");
        require(!Timeline.near(1000,1251),"expired sample must disappear");
        require(!Timeline.near(4000,1000),"seek must not show future plates");
        require(Timeline.time(61500).equals("01:01.5"),"minute rollover");
        require(Timeline.time(0).equals("00:00.0"),"start position");
        for(long interval:new long[]{100,250,500,1000,2000,5000}) {
            require(Timeline.near(10000,10000+interval/2,interval),"configured interval boundary");
            require(!Timeline.near(10000,10001+interval/2,interval),"configured interval expiration");
        }
        require(!Timeline.near(1000,1400,500),"old clip retains its narrow window");
        require(Timeline.near(1000,1400,1000),"new clip uses its own window");
        System.out.println("Timeline timestamp checks passed");
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
