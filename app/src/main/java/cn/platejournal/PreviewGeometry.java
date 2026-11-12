package cn.platejournal;

final class PreviewGeometry {
    static int previewIndex(int[][] sizes,int videoWidth,int videoHeight) {
        int chosen=-1;long best=0;
        for(int i=0;i<sizes.length;i++){
            int w=sizes[i][0],h=sizes[i][1];
            if(w>1920||h>1080||w>videoWidth||h>videoHeight||w<=0||h<=0)continue;
            if((long)w*videoHeight!=(long)h*videoWidth)continue;
            long area=(long)w*h;if(area>best){best=area;chosen=i;}
        }
        return chosen;
    }
    static int[] fit(int boxWidth,int boxHeight,int imageWidth,int imageHeight) {
        if(boxWidth<=0||boxHeight<=0||imageWidth<=0||imageHeight<=0)return new int[]{0,0};
        double scale=Math.min(boxWidth/(double)imageWidth,boxHeight/(double)imageHeight);
        return new int[]{Math.max(1,(int)Math.round(imageWidth*scale)),Math.max(1,(int)Math.round(imageHeight*scale))};
    }
    static int[] sample(int width,int height) {
        if(width<=0||height<=0)return new int[]{0,0};
        double scale=1280.0/Math.max(width,height);
        return new int[]{Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale))};
    }
}
