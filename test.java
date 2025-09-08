import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Main {

    // amix の duration モード
    public enum DurationMode { LONGEST, SHORTEST, FIRST }

    // 基準フォーマット（先頭入力から決まる）
    static final class Ref {
        final int sr, ch, sf;
        Ref(int sr, int ch, int sf){ this.sr=sr; this.ch=ch; this.sf=sf; }
    }

    // 複数のM4Aをミックスして1つのM4Aにする
    public static void mixM4AByFirst(
            final List<String> inputs,
            final String outM4aPath,
            final DurationMode durationMode,
            final boolean normalize,
            final Integer audioBitrate
    ) throws Exception {
        Objects.requireNonNull(inputs);
        if (inputs.isEmpty()) throw new IllegalArgumentException("inputs is empty");

        // 入力を開く
        List<FFmpegFrameGrabber> gs = new ArrayList<>(inputs.size());
        for (String in : inputs) {
            FFmpegFrameGrabber g = new FFmpegFrameGrabber(in);
            g.setOption("vn","1"); // 映像無効化
            g.start();
            gs.add(g);
        }

        // 基準フォーマットを先頭から決定
        FFmpegFrameGrabber g0 = gs.get(0);
        int sr = g0.getSampleRate()    > 0 ? g0.getSampleRate()    : 44100;
        int ch = g0.getAudioChannels() > 0 ? g0.getAudioChannels() : 2;
        if (ch != 1 && ch != 2) ch = 2;
        int sf = g0.getSampleFormat();
        if (sf < 0) sf = avutil.AV_SAMPLE_FMT_S16;
        Ref ref = new Ref(sr, ch, sf);

        // 出力レコーダ（.m4a = mp4コンテナ + AAC）
        FFmpegFrameRecorder rec = new FFmpegFrameRecorder(outM4aPath, ref.ch);
        rec.setFormat("mp4");
        rec.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        rec.setSampleRate(ref.sr);
        rec.setAudioChannels(ref.ch);
        rec.setAudioBitrate(audioBitrate != null ? audioBitrate : 192_000);
        rec.setOption("movflags", "+faststart");
        rec.start();

        // filtergraph を生成
        String filterDesc = buildFilter(gs.size(), ref, durationMode, normalize);
        FFmpegFrameFilter filter = new FFmpegFrameFilter(filterDesc, ref.ch);
        filter.setAudioInputs(gs.size()); // 入力数を必ず一致させる
        filter.setSampleRate(ref.sr);
        filter.start();

        try {
            boolean[] ended = new boolean[gs.size()];
            while (true) {
                int pushed = 0;
                for (int i = 0; i < gs.size(); i++) {
                    if (ended[i]) continue;
                    Frame f = gs.get(i).grabSamples();
                    if (f == null) { ended[i] = true; continue; }
                    if (f.samples != null) {
                        filter.pushSamples(i, f.sampleRate, f.audioChannels, ref.sf, f.samples);
                        pushed++;
                    }
                }
                Frame mixed;
                while ((mixed = filter.pullSamples()) != null) {
                    rec.recordSamples(mixed.sampleRate, mixed.audioChannels, mixed.samples);
                }
                if (allEnded(ended) || pushed == 0) break;
            }
            // flush
            Frame tail;
            while ((tail = filter.pullSamples()) != null) {
                rec.recordSamples(tail.sampleRate, tail.audioChannels, tail.samples);
            }
        } finally {
            try { filter.stop(); filter.close(); } catch (Exception ignore) {}
            try { rec.stop(); rec.release(); } catch (Exception ignore) {}
            for (FFmpegFrameGrabber g : gs) { try { g.stop(); g.release(); } catch (Exception ignore) {} }
        }
    }

    // filtergraph を構築
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
          .append(":duration=").append(durationToString(dm))
          .append(":normalize=").append(normalize ? "1" : "0");
        return fb.toString();
    }

    private static String durationToString(DurationMode mode) {
        switch (mode) {
            case LONGEST:  return "longest";
            case SHORTEST: return "shortest";
            case FIRST:    return "first";
            default:       return "longest";
        }
    }

    private static boolean allEnded(boolean[] ended) {
        for (boolean e : ended) if (!e) return false;
        return true;
    }

    // 実行例（フルパス）
    public static void main(String[] args) throws Exception {
        List<String> ins = List.of(
            "/Users/yukiono/Desktop/da8847f2-cbac-4cc8-8bd8-0bce2c863369.m4a",
            "/Users/yukiono/Desktop/da8847f2-cbac-4cc8-8bd8-0bce2c863369のコピー.m4a",
            "/Users/yukiono/Desktop/da8847f2-cbac-4cc8-8bd8-0bce2c863369のコピー 2.m4a"
        );
        mixM4AByFirst(ins, "/Users/yukiono/Desktop/mixed.m4a",
                DurationMode.LONGEST,
                true,
                192_000);
        System.out.println("done: mixed.m4a");
    }
}
