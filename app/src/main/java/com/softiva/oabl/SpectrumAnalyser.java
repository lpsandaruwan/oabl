package com.softiva.oabl;

/** Maps Android's signed complex FFT into bounded logarithmic display bands. No audio is retained. */
final class SpectrumAnalyser {
    static final int BANDS=32;
    private final int[] first=new int[BANDS],last=new int[BANDS];
    private int size,rate;
    boolean analyse(byte[] fft,int milliHz,float[] levels){
        if(fft==null||fft.length<4||milliHz<=0){java.util.Arrays.fill(levels,0);return false;}
        if(size!=fft.length||rate!=milliHz){
            size=fft.length;rate=milliHz;double hz=milliHz/1000d;
            double high=Math.min(16000,hz/2);
            for(int i=0;i<BANDS;i++){
                first[i]=Math.max(1,Math.min(size/2-1,(int)Math.ceil(60*Math.pow(high/60,i/(double)BANDS)*size/hz)));
                last[i]=Math.max(first[i]+1,Math.min(size/2,(int)Math.ceil(60*Math.pow(high/60,(i+1d)/BANDS)*size/hz)));
            }
        }
        boolean signal=false;
        for(int i=0;i<BANDS;i++){
            int power=0;
            for(int bin=first[i];bin<last[i];bin++){int real=fft[bin*2],imaginary=fft[bin*2+1];power=Math.max(power,real*real+imaginary*imaginary);}
            signal|=power>0;
            levels[i]=(float)Math.min(1,Math.log10(1+Math.sqrt(power))/Math.log10(182));
        }
        return signal;
    }
}
