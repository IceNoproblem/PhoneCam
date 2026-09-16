package com.phonecam;

import java.io.ByteArrayOutputStream;

/**
 * 极简 MPEG-TS 封装器。
 * 把 MediaCodec 输出的 H.264 Annex-B 裸流打包成标准 MPEG-TS，
 * 使 OBS 的「媒体源」可直接以 tcp:// 读取，无需任何中间进程。
 *
 * 结构：PAT(PID 0x0000) + PMT(PID 0x1000) + H.264 视频(PID 0x0100)
 */
class TsMuxer {

    private static final int PID_PAT = 0x0000;
    private static final int PID_PMT = 0x1000;
    private static final int PID_VIDEO = 0x0100;

    private final int fps;

    private int ccPat = 0;
    private int ccPmt = 0;
    private int ccVideo = 0;

    private long frameIdx = 0;

    TsMuxer(int fps) {
        this.fps = fps > 0 ? fps : 30;
    }

    /** 把一帧 Annex-B 数据打成 TS。key 目前不参与逻辑（H.264 自带 IDR 标记）。 */
    byte[] pack(byte[] annexB, boolean key) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(annexB.length + 1024);

        // PSI 每半秒重发一次，保证中途接入的解码器能拿到节目表
        if (frameIdx % Math.max(1, fps / 2) == 0) {
            writePsi(out);
        }

        long pts = frameIdx * 90000L / fps;   // 90kHz 时钟
        frameIdx++;

        writePes(out, annexB, pts);
        return out.toByteArray();
    }

    // ---------- PSI ----------

    private void writePsi(ByteArrayOutputStream out) {
        // ---- PAT ----
        ByteArrayOutputStream pat = new ByteArrayOutputStream();
        pat.write(0x00);                                        // table_id (PAT)
        pat.write(0xB0); pat.write(0x0D);                       // section_syntax=1, length=13
        pat.write(0x00); pat.write(0x01);                       // transport_stream_id = 1
        pat.write(0xC1);                                        // version=0, current_next=1
        pat.write(0x00); pat.write(0x00);                       // section_number, last_section_number
        pat.write(0x00); pat.write(0x01);                       // program_number = 1
        pat.write(0xE0 | ((PID_PMT >> 8) & 0x1F));
        pat.write(PID_PMT & 0xFF);                              // PMT pid
        byte[] patBody = pat.toByteArray();
        int patCrc = crc32(patBody, 0, patBody.length);
        pat.write((patCrc >> 24) & 0xFF);
        pat.write((patCrc >> 16) & 0xFF);
        pat.write((patCrc >> 8) & 0xFF);
        pat.write(patCrc & 0xFF);

        byte[] patSection = pat.toByteArray();
        byte[] patWithPointer = new byte[patSection.length + 1];
        patWithPointer[0] = 0x00;                               // pointer_field
        System.arraycopy(patSection, 0, patWithPointer, 1, patSection.length);
        writeTs(out, PID_PAT, 0, patWithPointer, patWithPointer.length);

        // ---- PMT ----
        ByteArrayOutputStream pmt = new ByteArrayOutputStream();
        pmt.write(0x02);                                        // table_id (PMT)
        pmt.write(0xB0); pmt.write(0x12);                       // section_syntax=1, length=18
        pmt.write(0x00); pmt.write(0x01);                       // program_number = 1
        pmt.write(0xC1);                                        // version=0, current_next=1
        pmt.write(0x00); pmt.write(0x00);                       // section/last
        pmt.write(0xE0 | ((PID_VIDEO >> 8) & 0x1F));
        pmt.write(PID_VIDEO & 0xFF);                            // PCR_PID = video pid
        pmt.write(0xF0); pmt.write(0x00);                       // program_info_length = 0
        // stream: H.264
        pmt.write(0x1B);                                        // stream_type = H.264
        pmt.write(0xE0 | ((PID_VIDEO >> 8) & 0x1F));
        pmt.write(PID_VIDEO & 0xFF);
        pmt.write(0xF0); pmt.write(0x00);                       // ES_info_length = 0
        byte[] pmtBody = pmt.toByteArray();
        int pmtCrc = crc32(pmtBody, 0, pmtBody.length);
        pmt.write((pmtCrc >> 24) & 0xFF);
        pmt.write((pmtCrc >> 16) & 0xFF);
        pmt.write((pmtCrc >> 8) & 0xFF);
        pmt.write(pmtCrc & 0xFF);

        byte[] pmtSection = pmt.toByteArray();
        byte[] pmtWithPointer = new byte[pmtSection.length + 1];
        pmtWithPointer[0] = 0x00;
        System.arraycopy(pmtSection, 0, pmtWithPointer, 1, pmtSection.length);
        writeTs(out, PID_PMT, 1, pmtWithPointer, pmtWithPointer.length);
    }

    // ---------- PES ----------

    private void writePes(ByteArrayOutputStream out, byte[] annexB, long pts) {
        ByteArrayOutputStream pes = new ByteArrayOutputStream(annexB.length + 32);

        pes.write(0x00); pes.write(0x00); pes.write(0x01);
        pes.write(0xE0);                                        // stream_id = video

        int payloadLen = 3 + 5 + annexB.length;                 // flags(2)+hdrlen(1)+PTS(5)+data
        if (payloadLen > 0xFFFF) {
            pes.write(0x00); pes.write(0x00);                   // 不限长
        } else {
            pes.write((payloadLen >> 8) & 0xFF);
            pes.write(payloadLen & 0xFF);
        }
        pes.write(0x80);                                        // marker '10'
        pes.write(0x80);                                        // PTS only
        pes.write(0x05);                                        // PES header data length

        pes.write((0x21 | (((int) (pts >> 30) & 0x07) << 1)) & 0xFF);
        pes.write((int) ((pts >> 22) & 0xFF));
        pes.write((0x01 | (((int) (pts >> 15) & 0x7F) << 1)) & 0xFF);
        pes.write((int) ((pts >> 7) & 0xFF));
        pes.write((0x01 | (((int) pts & 0x7F) << 1)) & 0xFF);

        pes.write(annexB, 0, annexB.length);

        byte[] pesBytes = pes.toByteArray();
        writeTs(out, PID_VIDEO, 2, pesBytes, pesBytes.length);
    }

    // ---------- TS 包 ----------

    /**
     * 把一段数据切成 188 字节 TS 包。
     * slot 0=PAT 1=PMT 2=VIDEO，用于各自独立的 continuity counter。
     */
    private void writeTs(ByteArrayOutputStream out, int pid, int slot, byte[] data, int len) {
        int pos = 0;
        int remain = len;
        boolean first = true;

        while (remain > 0) {
            int take = Math.min(184, remain);
            byte[] p = new byte[188];
            p[0] = 0x47;

            boolean pusi = first;                               // 每个 PES/PSI 首包置位
            p[1] = (byte) ((pusi ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            p[2] = (byte) (pid & 0xFF);

            int cc = slot == 0 ? ccPat : (slot == 1 ? ccPmt : ccVideo);

            if (take == 184) {
                p[3] = (byte) (0x10 | (cc & 0x0F));             // 仅 payload
                System.arraycopy(data, pos, p, 4, take);
            } else {
                int stuff = 184 - take;                         // 需要填充的字节数
                p[3] = (byte) (0x30 | (cc & 0x0F));             // adaptation + payload
                p[4] = (byte) (stuff - 1);                      // adaptation_field_length
                if (stuff > 1) {
                    p[5] = 0x00;                                // flags
                    for (int i = 6; i < 4 + stuff; i++) p[i] = (byte) 0xFF;
                }
                System.arraycopy(data, pos, p, 4 + stuff, take);
            }

            out.write(p, 0, 188);

            cc = (cc + 1) & 0x0F;
            if (slot == 0) ccPat = cc; else if (slot == 1) ccPmt = cc; else ccVideo = cc;

            pos += take;
            remain -= take;
            first = false;
        }
    }

    // ---------- CRC32 (MPEG-2) ----------

    private static int crc32(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 24;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80000000) != 0) crc = (crc << 1) ^ 0x04C11DB7;
                else crc <<= 1;
            }
        }
        return crc;
    }
}
