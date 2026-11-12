package main

import "testing"

func TestDeny(t *testing.T) {
    q:=query(); out:=deny(q)
    if out==nil || out[3]!=0x83 || out[0]!=q[0] || out[1]!=q[1] { t.Fatal("invalid NXDOMAIN") }
    if q[3]!=0 { t.Fatal("request mutated") }
    for i:=0;i<len(q)-1;i++ { if deny(q[:i])!=nil { t.Fatalf("truncated query accepted at %d",i) } }
    compressed:=append([]byte(nil),q...); compressed[12]=0xc0
    if deny(compressed)!=nil { t.Fatal("compressed label accepted") }
}
