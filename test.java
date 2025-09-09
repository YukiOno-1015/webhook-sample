package jp.co.test.test;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public enum DurationMode { LONGEST, SHORTEST, FIRST }

    static final class Ref {
        final int sr, ch, sf;
        Ref(int sr, int ch, int sf){ this.sr=sr; this.ch=ch; this.sf=sf; }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java -jar app.jar --out /path/to/mixed.flac in1.m4a in2.m4a [...]");
            System.exit(2);
        }

        String outPath = null;
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i]) && i + 1 < args.length) {
                outPath = args[++i];
            } else {
                inputs.add(args[i]);
            }
        }

        if (outPath == null) {
            System.err.println("Missing --out <output.flac>");
            System.exit(2);
        }
        if (inputs.size() < 2) {
            System.err.println("Need at least 2 input files.");
            System.exit(2);
        }

        try {
            mixFlacByFirst(inputs, outPath, DurationMode.LONGEST, true);
        } catch (Exception e) {
            log.error("Fatal error in mixing process", e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void mixFlacByFirst(
            final List<String> inputPaths,
            final String outFlacPath,
            final DurationMode durationMode,
            final boolean normalize
    ) throws Exception {
        Objects.requireNonNull(inputPaths);
        Objects.requireNonNull(outFlacPath);
        if (inputPaths.size() < 2) throw new IllegalArgumentException("Need >=2 inputs");

        log.info("Inputs: {}", inputPaths);
        log.info("Output: {}", outFlacPath);

        List<FFmpegFrameGrabber> gs = new ArrayList<>(inputPaths.size());
        FFmpegFrameRecorder rec = null;
        FFmpegFrameFilter filter = null;

        try {
            // 入力を開く
            for (String in : inputPaths) {
                FFmpegFrameGrabber g = new FFmpegFrameGrabber(in);
                g.setOption("vn","1");
                g.start();
                gs.add(g);
            }

            // 基準フォーマット
            FFmpegFrameGrabber g0 = gs.get(0);
            int sr = g0.getSampleRate() > 0 ? g0.getSampleRate() : 44100;
            int ch = g0.getAudioChannels() > 0 ? g0.getAudioChannels() : 1;
            if (ch != 1 && ch != 2) ch = 1; // モノかステレオに限定
            int sf = g0.getSampleFormat();
            if (sf < 0) sf = avutil.AV_SAMPLE_FMT_S16;
            Ref ref = new Ref(sr, ch, sf);

            log.info("Reference: {} Hz, {} ch, sampleFormat={}", ref.sr, ref.ch, ref.sf);

            // 出力レコーダ（FLAC）
            rec = new FFmpegFrameRecorder(outFlacPath, ref.ch);
            rec.setFormat("flac");
            rec.setAudioCodec(avcodec.AV_CODEC_ID_FLAC);
            rec.setSampleRate(ref.sr);
            rec.setAudioChannels(ref.ch);
            rec.start();

            // フィルタ構築
            String filterDesc = buildFilter(inputPaths.size(), ref, durationMode, normalize);
            log.info("Filter: {}", filterDesc);

            filter = new FFmpegFrameFilter(filterDesc, ref.ch);
            filter.setAudioInputs(inputPaths.size());
            filter.setSampleRate(ref.sr);
            filter.start();

            boolean[] ended = new boolean[gs.size()];
            while (true) {
                int pushed = 0;
                for (int i = 0; i < gs.size(); i++) {
                    if (ended[i]) continue;
                    try {
                        Frame f = gs.get(i).grabSamples();
                        if (f == null) { ended[i] = true; continue; }
                        if (f.samples != null) {
                            filter.pushSamples(i, f.sampleRate, f.audioChannels, ref.sf, f.samples);
                            pushed++;
                        }
                    } catch (Exception e) {
                        log.error("Error grabbing from input[{}]: {}", i, e.getMessage(), e);
                        ended[i] = true;
                    }
                }
                Frame mixed;
                while ((mixed = filter.pullSamples()) != null) {
                    try {
                        rec.recordSamples(mixed.sampleRate, mixed.audioChannels, mixed.samples);
                    } catch (Exception e) {
                        log.error("Error recording mixed frame: {}", e.getMessage(), e);
                    }
                }
                if (allEnded(ended) || pushed == 0) break;
            }
            Frame tail;
            while ((tail = filter.pullSamples()) != null) {
                rec.recordSamples(tail.sampleRate, tail.audioChannels, tail.samples);
            }
            log.info("Done: {}", outFlacPath);

        } catch (Exception e) {
            log.error("Mixing error", e);
            throw e; // main 側で stacktrace も吐く
        } finally {
            safeClose(filter, "filter");
            safeClose(rec, "recorder");
            for (int i = 0; i < gs.size(); i++) {
                safeClose(gs.get(i), "grabber[" + i + "]");
            }
        }
    }

    private static String buildFilter(int n, Ref ref, DurationMode dm, boolean normalize) {
        StringBuilder fb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            fb.append("[in").append(i).append("]")
              .append("aresample=").append(ref.sr).append(",");
            if (ref.ch == 2) {
                fb.append("pan=stereo|c0=c0|c1=c1");
            } else {
                fb.append("pan=mono|c0=0.5*c0+0.5*c1");
            }
            fb.append("[a").append(i).append("];");
        }
        for (int i = 0; i < n; i++) fb.append("[a").append(i).append("]");
        fb.append("amix=inputs=").append(n)
          .append(":duration=").append(dm==DurationMode.LONGEST?"longest":dm==DurationMode.SHORTEST?"shortest":"first")
          .append(":normalize=").append(normalize ? "1" : "0");
        return fb.toString();
    }

    private static boolean allEnded(boolean[] ended) {
        for (boolean e : ended) if (!e) return false;
        return true;
    }

    private static void safeClose(FFmpegFrameFilter f, String name) {
        if (f == null) return;
        try { f.stop(); } catch (Exception e) { log.warn("Error stopping {}: {}", name, e.getMessage(), e); }
        try { f.close(); } catch (Exception e) { log.warn("Error closing {}: {}", name, e.getMessage(), e); }
    }

    private static void safeClose(FFmpegFrameRecorder r, String name) {
        if (r == null) return;
        try { r.stop(); } catch (Exception e) { log.warn("Error stopping {}: {}", name, e.getMessage(), e); }
        try { r.release(); } catch (Exception e) { log.warn("Error releasing {}: {}", name, e.getMessage(), e); }
    }

    private static void safeClose(FFmpegFrameGrabber g, String name) {
        if (g == null) return;
        try { g.stop(); } catch (Exception e) { log.warn("Error stopping {}: {}", name, e.getMessage(), e); }
        try { g.release(); } catch (Exception e) { log.warn("Error releasing {}: {}", name, e.getMessage(), e); }
    }
}
