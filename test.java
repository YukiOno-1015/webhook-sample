import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
// クラス名・パッケージは自由に変更OK
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 複数の M4A(AAC) をミックスし、M4A(AAC/MP4コンテナ) で出力するユーティリティ（Java 11 / JavaCV 1.5.9）。
 * <p>先頭入力のサンプリング周波数/チャンネル数を基準に揃えて amix で合成します。</p>
 */
public final class AudioMixerM4A {

    private AudioMixerM4A() {}

    /** amix の duration モード */
    public enum DurationMode { LONGEST, SHORTEST, FIRST }

    /** 先頭入力から決まる基準フォーマット */
    public static final class ReferenceFormat {
        public final int sampleRate;     // 例: 44100, 48000, 22050 など
        public final int channels;       // 1 or 2 に丸める
        public final int sampleFormat;   // avutil.AV_SAMPLE_FMT_*
        public ReferenceFormat(int sr, int ch, int sf) {
            this.sampleRate = sr; this.channels = ch; this.sampleFormat = sf;
        }
    }

    /**
     * 複数の M4A(AAC) をミックスして .m4a で保存します（先頭入力の SR/CH を基準）。
     *
     * @param inputs        入力 M4A（他フォーマット混在可）
     * @param outM4aPath    出力ファイル例: "mixed.m4a"（MP4コンテナ）
     * @param weightsOrNull 各入力のゲイン。null=等配分／指定時は inputs と同数
     * @param durationMode  合成長（LONGEST/SHORTEST/FIRST）
     * @param normalize     合成結果の正規化（クリップ回避に有効）
     * @param audioBitrate  出力ビットレート（bps。例: 192_000）。null なら既定
     * @throws Exception 入出力やエンコード失敗時
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
        Objects.requireNonNull(outM4aPath, "outM4aPath");
        Objects.requireNonNull(durationMode, "durationMode");
        if (inputs.isEmpty()) throw new IllegalArgumentException("inputs is empty");

        // （任意）FFmpegログを見たい場合は一度だけ
        FFmpegLogCallback.set();

        // 1) 入力を開く（音声のみ扱う）
        final List<FFmpegFrameGrabber> grabbers = openGrabbers(inputs);

        // 2) 先頭入力から基準 SR/CH/sampleFormat を決定
        final ReferenceFormat ref = determineReferenceFormat(grabbers);

        // 3) 出力レコーダ（AAC / MP4コンテナ → .m4a）
        final FFmpegFrameRecorder recorder = createM4aRecorder(outM4aPath, ref, audioBitrate);

        // 4) フィルタグラフ（各入力を ref に合わせて amix）
        final String filterDesc = buildFilterGraphDescription(
                grabbers.size(), ref, weightsOrNull, durationMode, normalize
        );
        final FFmpegFrameFilter filter = createFilter(filterDesc, ref, grabbers.size());

        try {
            // 5) push（各入力）→ pull（合成）→ record（出力）
            pumpAudio(grabbers, filter, recorder, ref);

            // 6) 残データ排出
            flushFilter(filter, recorder);
        } finally {
            // 7) クリーンアップ
            closeQuietly(filter);
            closeQuietly(recorder);
            closeQuietly(grabbers);
        }
    }

    // -------------------- Steps --------------------

    /** 入力を開き、映像を無効化（-vn） */
    private static List<FFmpegFrameGrabber> openGrabbers(final List<String> inputs) throws Exception {
        final List<FFmpegFrameGrabber> list = new ArrayList<>(inputs.size());
        for (String in : inputs) {
            FFmpegFrameGrabber g = new FFmpegFrameGrabber(in);
            g.setOption("vn", "1"); // 映像無効化（-vn）
            g.start();
            list.add(g);
        }
        return list;
    }

    /** 先頭入力から SR/CH/sampleFormat を確定（CH は 1 or 2 に丸め、SF は grabber から） */
    private static ReferenceFormat determineReferenceFormat(final List<FFmpegFrameGrabber> gs) {
        FFmpegFrameGrabber g0 = gs.get(0);
        int sr = g0.getSampleRate() > 0 ? g0.getSampleRate() : 44100;
        int ch = g0.getAudioChannels() > 0 ? g0.getAudioChannels() : 2;
        if (ch != 1 && ch != 2) ch = 2;
        int sf = g0.getSampleFormat();
        if (sf < 0) sf = avutil.AV_SAMPLE_FMT_S16; // 念のためフォールバック
        return new ReferenceFormat(sr, ch, sf);
    }

    /** M4A(AAC) レコーダを生成・開始（MP4コンテナ。拡張子は .m4a でOK） */
    private static FFmpegFrameRecorder createM4aRecorder(
            final String outPath, final ReferenceFormat ref, final Integer audioBitrate
    ) throws Exception {
        FFmpegFrameRecorder rec = new FFmpegFrameRecorder(outPath, ref.channels);
        // MP4 コンテナ（拡張子 .m4a で問題なし）
        rec.setFormat("mp4");
        rec.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        rec.setSampleRate(ref.sampleRate);
        rec.setAudioChannels(ref.channels);
        if (audioBitrate != null && audioBitrate > 0) {
            rec.setAudioBitrate(audioBitrate);
        } else {
            rec.setAudioBitrate(192_000); // 無難な既定（必要に応じ調整）
        }
        // 再生互換性を向上
        rec.setOption("movflags", "+faststart");
        // iOS 互換性をより重視するなら下記でも良い（どちらか一方でOK）
        // rec.setFormat("ipod"); // 拡張子 .m4a のままでOK
        rec.start();
        return rec;
    }

    /** amix 用 filtergraph（出力ラベルは付けない） */
    private static String buildFilterGraphDescription(
            final int nInputs,
            final ReferenceFormat ref,
            final List<Double> weightsOrNull,
            final DurationMode durationMode,
            final boolean normalize
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

    /** FFmpegFrameFilter を作成・開始（AudioInputs は実N本） */
    private static FFmpegFrameFilter createFilter(
            final String filterDesc, final ReferenceFormat ref, final int nInputs
    ) throws Exception {
        FFmpegFrameFilter filter = new FFmpegFrameFilter(filterDesc, ref.channels);
        filter.setAudioInputs(nInputs);
        filter.setSampleRate(ref.sampleRate);
        filter.start();
        return filter;
    }

    /** push（各入力）→ pull（合成）→ record（出力）。JavaCV 1.5.9 の pushSamples 形式に注意。 */
    private static void pumpAudio(
            final List<FFmpegFrameGrabber> gs,
            final FFmpegFrameFilter filter,
            final FFmpegFrameRecorder rec,
            final ReferenceFormat ref
    ) throws Exception {
        boolean[] ended = new boolean[gs.size()];

        while (true) {
            int pushed = 0;
            for (int i = 0; i < gs.size(); i++) {
                if (ended[i]) continue;

                Frame f = gs.get(i).grabSamples();
                if (f == null) { ended[i] = true; continue; }
                if (f.samples != null) {
                    // 1.5.9: pushSamples(index, sr, ch, sampleFormat, samples...)
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
    }

    /** フィルタ内の残データを排出。 */
    private static void flushFilter(final FFmpegFrameFilter filter, final FFmpegFrameRecorder rec) throws Exception {
        Frame tail;
        while ((tail = filter.pullSamples()) != null) {
            rec.recordSamples(tail.sampleRate, tail.audioChannels, tail.samples);
        }
    }

    // -------------------- Helpers --------------------

    private static String durationToString(final DurationMode mode) {
        switch (mode) {
            case LONGEST:  return "longest";
            case SHORTEST: return "shortest";
            case FIRST:    return "first";
            default:       return "longest";
        }
    }
    private static String joinWeights(final List<Double> weights) {
        StringJoiner sj = new StringJoiner(" ");
        for (Double d : weights) sj.add(String.valueOf(d));
        return sj.toString();
    }
    private static boolean allEnded(final boolean[] ended) {
        for (boolean e : ended) if (!e) return false;
        return true;
    }
    private static void closeQuietly(final FFmpegFrameRecorder rec) {
        if (rec == null) return;
        try { rec.stop(); } catch (Exception ignored) {}
        try { rec.release(); } catch (Exception ignored) {}
    }
    private static void closeQuietly(final FFmpegFrameFilter filter) {
        if (filter == null) return;
        try { filter.stop(); } catch (Exception ignored) {}
        try { filter.close(); } catch (Exception ignored) {}
    }
    private static void closeQuietly(final List<FFmpegFrameGrabber> gs) {
        if (gs == null) return;
        for (FFmpegFrameGrabber g : gs) {
            if (g == null) continue;
            try { g.stop(); } catch (Exception ignored) {}
            try { g.release(); } catch (Exception ignored) {}
        }
    }

    // -------------------- Demo --------------------
    public static void main(String[] args) throws Exception {
        List<String> ins = List.of("a.m4a", "b.m4a", "c.m4a");
        mixM4AByFirst(
                ins,
                "mixed.m4a",
                null,                 // 等配分（例: List.of(1.0, 0.8, 0.6) でもOK）
                DurationMode.LONGEST, // 最長に合わせる
                true,                 // normalize でクリップ抑止
                192_000               // 出力ビットレート（bps）
        );
        System.out.println("done: mixed.m4a");
    }
}
