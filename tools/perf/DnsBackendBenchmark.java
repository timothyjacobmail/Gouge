import java.util.*;
import java.nio.charset.StandardCharsets;

/** Same bounded local-denial workload as tools/perf/go-dns, not an Android/JNI speed claim. */
public final class DnsBackendBenchmark {
    static final Set<String> BLOCKED = Set.of("images.example.com", "tracker.example.org");
    static volatile int sink;
    static byte[] deny(byte[] q) {
        if (q.length<17 || q[4]!=0 || q[5]!=1) return null;
        int i=12; StringBuilder host=new StringBuilder();
        while(i<q.length) {
            int n=q[i++]&255;
            if(n==0) {
                if(i+4>q.length) return null;
                String name=host.toString().toLowerCase(Locale.ROOT);
                while(name.contains(".")) {
                    if(BLOCKED.contains(name)) {
                        byte[] out=Arrays.copyOf(q,i+4); out[2]=(byte)0x81; out[3]=(byte)0x83;
                        Arrays.fill(out,6,12,(byte)0); return out;
                    }
                    name=name.substring(name.indexOf('.')+1);
                }
                return null;
            }
            if(n>63 || i+n>q.length) return null;
            if(host.length()>0) host.append('.'); host.append(new String(q,i,n,StandardCharsets.US_ASCII)); i+=n;
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();
        b.write(new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0});
        for(String label:"a.images.example.com".split("\\.")) {b.write(label.length()); b.write(label.getBytes(StandardCharsets.US_ASCII));}
        b.write(new byte[]{0,0,1,0,1}); byte[] q=b.toByteArray();
        for(int i=0;i<20000;i++) sink=deny(q).length;
        final int batch=20000;
        long[] times=new long[50]; long start=System.nanoTime();
        for(int i=0;i<times.length;i++){long t=System.nanoTime(); int checksum=0; for(int j=0;j<batch;j++) checksum+=deny(q).length; sink=checksum; times[i]=(System.nanoTime()-t)/batch;}
        long elapsed=System.nanoTime()-start; Arrays.sort(times);
        System.out.printf(Locale.ROOT,"{\"runtime\":\"Java\",\"iterations\":%d,\"mean_batch_ns\":%.1f,\"p50_ns\":%d,\"p95_ns\":%d}%n", times.length*batch,(double)elapsed/(times.length*batch),times[times.length/2],times[times.length*95/100]);
    }
}
