import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public class Main {

    /** amix の duration モード */
    public enum DurationMode { LONGEST, SHORTEST, FIRST }

    /** 先頭入力から決まる基準フォーマット */
    public static final class ReferenceFormat {
        public final int sampleRate;
        public final int channels;
        public final int sampleFormat;
        public ReferenceFormat(int sr, int ch, int sf) {
            this.sampleRate = sr;
            this.channels = ch;
            this.sampleFormat = sf;
        }
    }

    /**
     * 複数の音声ファイル（M4A含む）をミックスし、.m4a(AAC/MP4コンテナ)で出力します。
     *
     * @param inputs        入力音声ファイルのリスト（先頭を基準にSR/CHを決定）
     * @param outM4aPath    出力ファイルのパス（例: "mixed.m4a"）
     * @param weightsOrNull 入力ごとのゲイン。nullなら等配分。指定時は inputs と同数
     * @param durationMode  合成長の決定方法（LONGEST/SHORTEST/FIRST）
     * @param normalize     amix の normalize フラグ
     * @param audioBitrate  出力ビットレート（bps）。nullなら192kbps
     */
    public static void mixM4AByFirst(
            final List<String> inputs,
            final String outM4aPath,
            final List<Double> weightsOrNull,
            final DurationMode durationMode,
            final boolean normalize,
            final Integer audioBitrate
    ) throws Exception {

        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty()) throw new IllegalArgumentException("inputs is empty");

        // ★ FFmpegログを有効化（詳細エラーメッセージを得るため）
        FFmpegLogCallback.set();

        // 1) 入力を開く
        final List<FFmpegFrameGrabber> grabbers = new ArrayList<>(inputs.size());
        for (String in : inputs) {
            FFmpegFrameGrabber g = new FFmpegFrameGrabber(in);
            g.setOption("vn", "1"); // 映像は無効化
            g.start();
            grabbers.add(g);
        }

        // 2) 先頭から基準フォーマットを決定
        FFmpegFrameGrabber g0 = grabbers.get(0);
        int sr = g0.getSampleRate() > 0 ? g0.getSampleRate() : 44100;
        int ch = g0.getAudioChannels() > 0 ? g0.getAudioChannels() : 2;
        if (ch != 1 && ch != 2) ch = 2;
        int sf = g0.getSampleFormat();
        if (sf < 0) sf = avutil.AV_SAMPLE_FMT_S16;
        ReferenceFormat ref = new ReferenceFormat(sr, ch, sf);

        // 3) 出力レコーダを準備（M4A=MP4コンテナ＋AACコーデック）
        FFmpegFrameRecorder rec = new FFmpegFrameRecorder(outM4aPath, ref.channels);
        rec.setFormat("mp4");
        rec.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        rec.setSampleRate(ref.sampleRate);
        rec.setAudioChannels(ref.channels);
        rec.setAudioBitrate(audioBitrate != null ? audioBitrate : 192_000);
        rec.setOption("movflags", "+faststart");
        rec.start();

        // 4) フィルタグラフを組み立て
        String filterDesc = buildFilterGraphDescription(
                grabbers.size(), ref, weightsOrNull, durationMode, normalize
        );
        System.out.println("FILTER >>> " + filterDesc); // デバッグ出力
        FFmpegFrameFilter filter = new FFmpegFrameFilter(filterDesc, ref.channels);
        filter.setAudioInputs(grabbers.size());
        filter.setSampleRate(ref.sampleRate);
        filter.start();

        try {
            boolean[] ended = new boolean[grabbers.size()];
            while (true) {
                int pushed = 0;
                for (int i = 0; i < grabbers.size(); i++) {
                    if (ended[i]) continue;
                    Frame f = grabbers.get(i).grabSamples();
                    if (f == null) { ended[i] = true; continue; }
                    if (f.samples != null) {
                        filter.pushSamples(i, f.sampleRate, f.audioChannels, ref.sampleFormat, f.samples);
                        pushed++;
                    }
                }
                Frame mixed;
                while ((mixed = filter.pullSamples()) != null) {
                    rec.recordSamples(mixed.sampleRate, mixed.audioChannels, mixed.samples);
                }
                if (allEnded(ended) || pushed == 0) break;
            }
            // 残りを排出
            Frame tail;
            while ((tail = filter.pullSamples()) != null) {
                rec.recordSamples(tail.sampleRate, tail.audioChannels, tail.samples);
            }
        } finally {
            try { filter.stop(); filter.close(); } catch (Exception ignore) {}
            try { rec.stop(); rec.release(); } catch (Exception ignore) {}
            for (FFmpegFrameGrabber g : grabbers) {
                try { g.stop(); g.release(); } catch (Exception ignore) {}
            }
        }
    }

    /** amix 用の filtergraph を構築（出力ラベルは付けない） */
    private static String buildFilterGraphDescription(
            int nInputs, ReferenceFormat ref,
            List<Double> weightsOrNull, DurationMode durationMode, boolean normalize
    ) {
        StringBuilder fb = new StringBuilder();
        for (int i = 0; i < nInputs; i++) {
            fb.append("[in").append(i).append("]")
              .append("aresample=").append(ref.sampleRate).append(",");
            if (ref.channels == 2) {
                fb.append("pan=stereo|c0=c0|c1=c1");
            } else {
                fb.append("pan=mono|c0=0.5*c0+0.5*c1");
            }
            fb.append("[a").append(i).append("];");
        }
        for (int i = 0; i < nInputs; i++) fb.append("[a").append(i).append("]");
        fb.append("amix=inputs=").append(nInputs)
          .append(":duration=").append(durationToString(durationMode));
        if (weightsOrNull != null) {
            if (weightsOrNull.size() != nInputs)
                throw new IllegalArgumentException("weights size must equal inputs size");
            fb.append(":weights=").append(joinWeights(weightsOrNull));
        }
        fb.append(":normalize=").append(normalize ? "1" : "0");
        return fb.toString();
    }

    private static String durationToString(DurationMode mode) {
        switch (mode) {
            case LONGEST: return "longest";
            case SHORTEST: return "shortest";
            case FIRST: return "first";
            default: return "longest";
        }
    }

    private static String joinWeights(List<Double> weights) {
        StringJoiner sj = new StringJoiner(" ");
        for (Double d : weights) sj.add(String.valueOf(d));
        return sj.toString();
    }

    private static boolean allEnded(boolean[] ended) {
        for (boolean e : ended) if (!e) return false;
        return true;
    }

    // -------------------- 実行例 --------------------
    public static void main(String[] args) throws Exception {
        // ★ main の最初でも呼んでOK（二重でも害はない）
        FFmpegLogCallback.set();

        List<String> ins = List.of("a.m4a", "b.m4a", "c.m4a");
        mixM4AByFirst(
                ins,
                "mixed.m4a",
                null,                 // 等配分
                DurationMode.LONGEST, // 最長に合わせる
                true,                 // normalize 有効
                192_000               // 出力ビットレート
        );
        System.out.println("done: mixed.m4a");
    }
}
