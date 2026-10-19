using System;
using System.IO;
using System.Text;

public static class PatchShow {
    static int U2(byte[] b, int o) { return (b[o] << 8) | b[o + 1]; }

    public static string Run(string path) {
        byte[] d = File.ReadAllBytes(path);
        if (d[0] != 0xCA || d[1] != 0xFE) return "bad magic";
        int cpCount = U2(d, 8);
        int i = 10;
        for (int idx = 1; idx < cpCount; idx++) {
            byte tag = d[i];
            switch (tag) {
                case 1: i += 3 + U2(d, i + 1); break;
                case 7: case 8: case 16: case 19: case 20: i += 3; break;
                case 3: case 4: i += 5; break;
                case 5: case 6: i += 9; idx++; break;
                case 9: case 10: case 11: case 12: case 17: case 18: i += 5; break;
                case 15: i += 4; break;
                default: return "bad tag " + tag + " at " + i;
            }
        }
        int poolEnd = i;
        byte[] u1 = Utf("d");
        byte[] u2 = Utf("(Ljava/lang/Object;)V");
        byte[] u3 = Utf("br/com/gertec/ppcomp/a");
        int n = cpCount;
        byte[] extra = new byte[u1.Length + u2.Length + u3.Length + 3 + 5 + 5];
        int e = 0;
        Buffer.BlockCopy(u1, 0, extra, e, u1.Length); e += u1.Length;
        Buffer.BlockCopy(u2, 0, extra, e, u2.Length); e += u2.Length;
        Buffer.BlockCopy(u3, 0, extra, e, u3.Length); e += u3.Length;
        extra[e++] = 7; extra[e++] = (byte)((n + 2) >> 8); extra[e++] = (byte)(n + 2);
        extra[e++] = 12; extra[e++] = (byte)(n >> 8); extra[e++] = (byte)n; extra[e++] = (byte)((n + 1) >> 8); extra[e++] = (byte)(n + 1);
        int methodRef = n + 5;
        extra[e++] = 10; extra[e++] = (byte)((n + 3) >> 8); extra[e++] = (byte)(n + 3); extra[e++] = (byte)((n + 4) >> 8); extra[e++] = (byte)(n + 4);
        if (e != extra.Length) return "extra size " + e + " " + extra.Length;
        byte[] outb = new byte[d.Length + extra.Length];
        Buffer.BlockCopy(d, 0, outb, 0, poolEnd);
        Buffer.BlockCopy(extra, 0, outb, poolEnd, extra.Length);
        Buffer.BlockCopy(d, poolEnd, outb, poolEnd + extra.Length, d.Length - poolEnd);
        int newCount = cpCount + 6;
        outb[8] = (byte)(newCount >> 8);
        outb[9] = (byte)newCount;
        byte[] oldCall = new byte[] { 0xB6, 0x01, 0xB8 };
        byte[] neuCall = new byte[] { 0xB8, (byte)(methodRef >> 8), (byte)methodRef };
        int hits = 0;
        for (int p = poolEnd + extra.Length; p <= outb.Length - 3; p++) {
            if (outb[p] == oldCall[0] && outb[p + 1] == oldCall[1] && outb[p + 2] == oldCall[2]) {
                outb[p] = neuCall[0]; outb[p + 1] = neuCall[1]; outb[p + 2] = neuCall[2];
                hits++;
            }
        }
        if (hits != 2) return "refuse calls=" + hits + " methodRef=" + methodRef;
        File.WriteAllBytes(path, outb);
        return "cp=" + cpCount + " methodRef=" + methodRef + " calls=" + hits;
    }

    static byte[] Utf(string s) {
        byte[] raw = Encoding.UTF8.GetBytes(s);
        byte[] b = new byte[3 + raw.Length];
        b[0] = 1;
        b[1] = (byte)(raw.Length >> 8);
        b[2] = (byte)raw.Length;
        Buffer.BlockCopy(raw, 0, b, 3, raw.Length);
        return b;
    }
}
