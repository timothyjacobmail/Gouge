// Isolated Go DNS backend experiment. Not linked into the Android APK.
package main

import (
    "encoding/json"
    "fmt"
    "net"
    "os"
    "sort"
    "strings"
    "time"
)

var blocked = map[string]bool{"images.example.com": true, "tracker.example.org": true}
var sink int

func deny(q []byte) []byte {
    if len(q) < 17 || q[4] != 0 || q[5] != 1 { return nil }
    i := 12
    var host strings.Builder
    for i < len(q) {
        n := int(q[i]); i++
        if n == 0 {
            if i+4 > len(q) { return nil }
            name := strings.ToLower(host.String())
            for strings.Contains(name, ".") {
                if blocked[name] {
                    answer := append([]byte(nil), q[:i+4]...)
                    answer[2], answer[3] = 0x81, 0x83
                    for j:=6; j<12; j++ { answer[j]=0 }
                    return answer
                }
                name = name[strings.IndexByte(name, '.')+1:]
            }
            return nil
        }
        if n > 63 || i+n > len(q) { return nil }
        if host.Len()>0 { host.WriteByte('.') }; host.Write(q[i:i+n]); i+=n
    }
    return nil
}

func query() []byte {
    q:=[]byte{0x12,0x34,1,0,0,1,0,0,0,0,0,0}
    for _,label:=range strings.Split("a.images.example.com", ".") { q=append(q,byte(len(label))); q=append(q,[]byte(label)...) }
    return append(q,0,0,1,0,1)
}

func main() {
    if len(os.Args)>1 && os.Args[1]=="server" {
        // Loopback only: this prototype is not a production DNS daemon.
        conn,err:=net.ListenPacket("udp4","127.0.0.1:0"); if err!=nil { panic(err) }; defer conn.Close()
        fmt.Println(conn.LocalAddr().String())
        buffer:=make([]byte,4096)
        for { n,addr,e:=conn.ReadFrom(buffer); if e!=nil { return }; if answer:=deny(buffer[:n]); answer!=nil { conn.WriteTo(answer,addr) } }
    }
    q:=query()
    for i:=0;i<20000;i++ { if deny(q)==nil { panic("incorrect denial") } }
    samples:=make([]int64,50)
    const batch = 20000
    start:=time.Now()
    for i:=range samples { t:=time.Now(); checksum:=0; for j:=0;j<batch;j++ { answer:=deny(q); if answer==nil { panic("incorrect denial") }; checksum+=len(answer) }; sink=checksum; samples[i]=time.Since(t).Nanoseconds()/batch }
    elapsed:=time.Since(start); sort.Slice(samples,func(i,j int)bool{return samples[i]<samples[j]})
    json.NewEncoder(os.Stdout).Encode(map[string]any{"runtime":"Go", "iterations":len(samples)*batch, "mean_batch_ns":float64(elapsed.Nanoseconds())/float64(len(samples)*batch), "p50_ns":samples[len(samples)/2], "p95_ns":samples[len(samples)*95/100]})
}
