package cn.platejournal;
import java.util.Arrays;
public class PreviewGeometryTest {
    public static void main(String[] args) {
        equal(PreviewGeometry.fit(900,500,1920,1080),new int[]{889,500},"landscape fitted overlay");
        equal(PreviewGeometry.fit(500,900,1080,1920),new int[]{500,889},"portrait fitted overlay");
        equal(PreviewGeometry.fit(0,0,1920,1080),new int[]{0,0},"defer unmeasured layout");
        equal(PreviewGeometry.sample(480,270),new int[]{1280,720},"small landscape pane retains inference size");
        equal(PreviewGeometry.sample(270,480),new int[]{720,1280},"portrait inference aspect");
        int[][] outputs={{640,480},{1280,720},{1920,1080},{3840,2160}};
        require(PreviewGeometry.previewIndex(outputs,3840,2160)==2,"4K records with 1080p preview");
        require(PreviewGeometry.previewIndex(new int[][]{{1280,720},{1920,1080}},3840,2160)==1,"4K does not require 4K preview support");
        require(PreviewGeometry.previewIndex(outputs,640,480)==0,"4:3 preview aspect");
        require(PreviewGeometry.previewIndex(new int[][]{{640,480}},3840,2160)==-1,"reject mismatched preview aspect");
        System.out.println("Landscape geometry, sampling and 4K preview selection checks passed");
    }
    private static void equal(int[] a,int[] b,String why){require(Arrays.equals(a,b),why+Arrays.toString(a));}
    private static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
}
