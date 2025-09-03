import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 任意本数の音声を「先頭入力のサンプリング周波数/チャンネル数」に合わせてミックスし、FLAC で出力するユーティリティ。
 * <p>
 * すべての入力を {@code aresample}（SR統一）と {@code pan}（CH統一）で「先頭入力の仕様」に正規化したのち、
 * FFmpeg の {@code amix} フィルタを用いて合成します。
 * </p>
 *
 * <h3>前提</h3>
 * <ul>
 *   <li>Java 11</li>
 *   <li>JavaCV 1.5.9（Bytedeco）。映像無効化は {@code setOption("vn","1")} を用いる</li>
 * </ul>
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>先頭がモノラルなら他入力をダウンミックス（左右平均）</li>
 *   <li>先頭がステレオなら他入力をステレオ化（モノは L=R）</li>
 *   <li>3ch 以上など特殊レイアウトは安全側で 2ch に丸め</li>
 * </ul>
 *
 * @author ChatGPT
 * @since 1.0
 */
public final class AudioMixerFlacJ11Doc {

    private AudioMixerFlacJ11Doc() {}

    /**
     * amix の duration モード（合成長の決定方法）。
     */
    public enum DurationMode {
        /** 最長の入力長に合わせる（足りない部分は無音パディング） */
        LONGEST,
        /** 重なっている最短の長さに合わせる（共通区間のみ） */
        SHORTEST,
        /** 1本目の入力長に合わせる */
        FIRST
    }

    /**
     * ミックス時に使用する基準フォーマット（先頭入力から決定）。
     */
    public static final class ReferenceFormat {
        /** サンプリング周波数（例: 44100/48000） */
        public final int sampleRate;
        /** チャンネル数（1=mono, 2=stereo。3ch 以上は 2 に丸め） */
        public final int channels;

        /**
         * @param sampleRate サンプリング周波数（Hz）
         * @param channels   チャンネル数（1 または 2 を推奨）
         */
        public ReferenceFormat(int sampleRate, int channels) {
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    // ====================================================================================
    // Public API
    // ====================================================================================

    /**
     * 先頭入力の SR/CH にそろえて N 本の音声をミックスし、FLAC（可逆圧縮）として保存します。
     * <p>
     * 事前にファイル存在/アクセス権などは呼び出し側で確認しておくことを推奨します。
     * 入力はローカルディスク上に置くと I/O の安定性が高まります。
     * </p>
     *
     * @param inputs            入力音声ファイルパスのリスト（先頭エントリを基準とする）。<br>
     *                          少なくとも 1 要素以上。2 本以上でミックス効果がある
     * @param outFlacPath       出力 FLAC ファイルのパス（例: {@code "mixed.flac"}）
     * @param weightsOrNull     各入力のゲイン重み。{@code null} の場合は等配分。<br>
     *                          指定する場合は {@code inputs.size()} と同じサイズで、各要素は {@code 0.0} 以上を推奨
     * @param durationMode      amix の duration 指定（{@link DurationMode}）
     * @param normalize         amix の normalize フラグ。{@code true} で合成結果を自動正規化（クリップ回避に有効）
     * @param flacCompressionLv FLAC 圧縮レベル（{@code 0}～{@code 12}）。{@code null} なら未指定（エンコーダ既定値）
     * @throws IllegalArgumentException {@code inputs} が空 / {@code weightsOrNull} のサイズ不一致 など
     * @throws Exception                入出力やエンコード処理に失敗した場合
     */
    public static void mixToFlacByFirst(
            final List<String> inputs,
            final String outFlacPath,
            final List<Double> weightsOrNull,
            final DurationMode durationMode,
            final boolean normalize,
            final Integer flacCompressionLv
    ) throws Exception {

        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(outFlacPath, "outFlacPath");
        Objects.requireNonNull(durationMode, "durationMode");

        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs is empty");
        }

        // 1) 入力を開く（映像は読み込まない）
        final List<FFmpegFrameGrabber> grabbers = openGrabbers(inputs);

        // 2) 先頭入力から基準 SR/CH を決める（安全に 1 or 2ch に丸め）
        final ReferenceFormat ref = determineReferenceFormat(grabbers);

        // 3) 出力 Recorder (FLAC) を準備
        final FFmpegFrameRecorder recorder = createFlacRecorder(outFlacPath, ref, flacCompressionLv);

        // 4) フィルタグラフ（各入力を ref に合わせて amix）
        final String filterDesc = buildFilterGraphDescription(
                grabbers.size(), ref, weightsOrNull, durationMode, normalize
        );
        final FFmpegFrameFilter filter = createFilter(filterDesc, ref);

        try {
            // 5) サンプルを流す（push→pull→record）
            pumpAudio(grabbers, filter, recorder);

            // 6) 取りこぼしを排出
            flushFilter(filter, recorder);
        } finally {
            // 7) クリーンアップ（例外を握りつぶして安全に閉じる）
            closeQuietly(filter);
            closeQuietly(recorder);
            closeQuietly(grabbers);
        }
    }

    // ====================================================================================
    // Steps
    // ====================================================================================

    /**
     * 入力ファイルを開き、音声のみを対象にする設定で {@link FFmpegFrameGrabber} を返します。
     *
     * @param inputs 入力音声ファイルのパス一覧
     * @return 開始済みの {@link FFmpegFrameGrabber} のリスト（順序は {@code inputs} に対応）
     * @throws Exception 入力のオープン/開始に失敗した場合
     */
    private static List<FFmpegFrameGrabber> openGrabbers(final List<String> inputs) throws Exception {
        final List<FFmpegFrameGrabber> list = new ArrayList<>(inputs.size());
        for (String in : inputs) {
            FFmpegFrameGrabber g = new FFmpegFrameGrabber(in);
            // JavaCV 1.5.9：setVideoDisable が無いケースがあるため -vn と同義の option を設定
            g.setOption("vn", "1"); // 映像を無効化（ffmpeg CLI の -vn）
            g.start();
            list.add(g);
        }
        return list;
    }

    /**
     * 先頭入力から基準 SR/CH を決定します。チャンネルは安全側に 1（モノ）または 2（ステレオ）に丸めます。
     *
     * @param gs 開始済みの {@link FFmpegFrameGrabber} リスト。少なくとも 1 要素
     * @return 先頭入力を基準とした {@link ReferenceFormat}
     * @throws IllegalArgumentException {@code gs} が空の場合
     */
    private static ReferenceFormat determineReferenceFormat(final List<FFmpegFrameGrabber> gs) {
        if (gs.isEmpty()) {
            throw new IllegalArgumentException("grabbers is empty");
        }
        int sr = gs.get(0).getSampleRate() > 0 ? gs.get(0).getSampleRate() : 44100;
        int ch = gs.get(0).getAudioChannels() > 0 ? gs.get(0).getAudioChannels() : 2;
        if (ch != 1 && ch != 2) {
            ch = 2; // 3ch 以上や 0 の場合はステレオに丸める
        }
        return new ReferenceFormat(sr, ch);
    }

    /**
     * FLAC 出力の {@link FFmpegFrameRecorder} を生成・開始します。
     *
     * @param outPath       出力ファイルパス（例: {@code "mixed.flac"}）
     * @param ref           基準フォーマット（サンプリング周波数/チャンネル数）
     * @param compressionLv FLAC 圧縮レベル（{@code 0}～{@code 12}）。{@code null} で未指定
     * @return 開始済みの {@link FFmpegFrameRecorder}
     * @throws Exception レコーダの開始に失敗した場合
     */
    private static FFmpegFrameRecorder createFlacRecorder(
            final String outPath, final ReferenceFormat ref, final Integer compressionLv
    ) throws Exception {
        FFmpegFrameRecorder rec = new FFmpegFrameRecorder(outPath, ref.channels);
        rec.setFormat("flac");
        rec.setAudioCodec(avcodec.AV_CODEC_ID_FLAC);
        rec.setSampleRate(ref.sampleRate);
        rec.setAudioChannels(ref.channels);
        if (compressionLv != null) {
            // 0(高速/低圧縮) ～ 12(低速/高圧縮)
            rec.setOption("compression_level", String.valueOf(compressionLv));
        }
        rec.start();
        return rec;
    }

    /**
     * amix 用のフィルタグラフを構築します。各入力を {@code aresample}+{@code pan} で基準（ref）に合わせてから amix へ結線します。
     *
     * @param nInputs       入力本数（2 以上推奨）
     * @param ref           基準フォーマット（先頭入力由来）
     * @param weightsOrNull 入力ごとのゲイン重み。{@code null} で等配分。指定時は {@code nInputs} と同数
     * @param durationMode  合成長の決定方法（amix: duration）
     * @param normalize     合成出力の正規化フラグ（amix: normalize）
     * @return FFmpeg の filtergraph 文字列（例：{@code [in0]aresample=48000,pan=stereo|c0=c0|c1=c1[a0];...amix=inputs=3:duration=longest:weights=1 0.8 0.5:normalize=1[out0]}）
     * @throws IllegalArgumentException {@code weightsOrNull} のサイズが {@code nInputs} と一致しない場合
     */
    private static String buildFilterGraphDescription(
            final int nInputs,
            final ReferenceFormat ref,
            final List<Double> weightsOrNull,
            final DurationMode durationMode,
            final boolean normalize
    ) {
        StringBuilder fb = new StringBuilder();

        // 前段：各入力 [in{i}] → aresample(+pan) → [a{i}]
        for (int i = 0; i < nInputs; i++) {
            fb.append("[in").append(i).append("]")
              .append("aresample=").append(ref.sampleRate).append(",");
            if (ref.channels == 2) {
                // ステレオに整形：モノ入力は L=R で拡張（c1 が無ければ 0 と解釈され安全）
                fb.append("pan=stereo|c0=c0|c1=c1");
            } else {
                // モノに整形：ステレオなら左右平均、モノ入力もそのまま平均として扱える
                fb.append("pan=mono|c0=0.5*c0+0.5*c1");
            }
            fb.append("[a").append(i).append("];");
        }

        // amix の入力列挙 [a0][a1]...[aN-1]
        for (int i = 0; i < nInputs; i++) {
            fb.append("[a").append(i).append("]");
        }

        // amix パラメータ
        fb.append("amix=inputs=").append(nInputs)
          .append(":duration=").append(durationToString(durationMode));

        if (weightsOrNull != null) {
            if (weightsOrNull.size() != nInputs) {
                throw new IllegalArgumentException("weights size must equal inputs size");
            }
            fb.append(":weights=").append(joinWeights(weightsOrNull));
        }

        fb.append(":normalize=").append(normalize ? "1" : "0")
          .append("[out0]");

        return fb.toString();
    }

    /**
     * {@link FFmpegFrameFilter} を作成・開始します。
     *
     * @param filterDesc フィルタグラフ文字列（例：{@code [in0]...amix=inputs=2:duration=longest[out0]}）
     * @param ref        基準フォーマット（チャンネル/サンプルレート）
     * @return 開始済みの {@link FFmpegFrameFilter}
     * @throws Exception フィルタの開始に失敗した場合
     */
    private static FFmpegFrameFilter createFilter(final String filterDesc, final ReferenceFormat ref) throws Exception {
        FFmpegFrameFilter filter = new FFmpegFrameFilter(filterDesc, ref.channels);
        // 複数入力であることを明示（[inX] の個数から推定）
        int nInputs = countInputsInFilter(filterDesc);
        filter.setAudioInputs(nInputs);
        filter.setSampleRate(ref.sampleRate);
        filter.start();
        return filter;
    }

    /**
     * 各入力からサンプルを取り出してフィルタへプッシュし、合成結果をレコーダへ書き出します。
     *
     * @param gs     開始済みのグラバー一覧
     * @param filter 開始済みのフィルタ
     * @param rec    開始済みのレコーダ
     * @throws Exception 入出力/エンコードに失敗した場合
     */
    private static void pumpAudio(
            final List<FFmpegFrameGrabber> gs,
            final FFmpegFrameFilter filter,
            final FFmpegFrameRecorder rec
    ) throws Exception {
        boolean[] ended = new boolean[gs.size()];

        while (true) {
            int pushedThisRound = 0;

            // 各入力から 1 フレームずつ（あるものだけ）push
            for (int i = 0; i < gs.size(); i++) {
                if (ended[i]) continue;

                Frame f = gs.get(i).grabSamples();
                if (f == null) { // この入力が終了
                    ended[i] = true;
                    continue;
                }
                if (f.samples != null) {
                    // 入力 i として push（フィルタ前段で aresample+pan 済みになる）
                    filter.pushSamples(i, f.sampleRate, f.audioChannels, f.samples);
                    pushedThisRound++;
                }
            }

            // フィルタからミックス結果を pull → レコーダへ
            Frame mixed;
            while ((mixed = filter.pullSamples()) != null) {
                rec.recordSamples(mixed.sampleRate, mixed.audioChannels, mixed.samples);
            }

            // 全入力が終わった or この周回で何も push できなければ終了
            if (allEnded(ended) || pushedThisRound == 0) {
                break;
            }
        }
    }

    /**
     * フィルタに残っているサンプルをすべて排出してレコーダへ書き込みます。
     *
     * @param filter 開始済みのフィルタ
     * @param rec    開始済みのレコーダ
     * @throws Exception 書き込みに失敗した場合
     */
    private static void flushFilter(final FFmpegFrameFilter filter, final FFmpegFrameRecorder rec) throws Exception {
        Frame tail;
        while ((tail = filter.pullSamples()) != null) {
            rec.recordSamples(tail.sampleRate, tail.audioChannels, tail.samples);
        }
    }

    // ====================================================================================
    // Helpers
    // ====================================================================================

    /**
     * {@link DurationMode} を FFmpeg の文字列表現へ変換します。
     *
     * @param mode 変換対象
     * @return {@code "longest"} / {@code "shortest"} / {@code "first"}
     */
    private static String durationToString(final DurationMode mode) {
        switch (mode) {
            case LONGEST:  return "longest";
            case SHORTEST: return "shortest";
            case FIRST:    return "first";
            default:       return "longest";
        }
    }

    /**
     * ゲイン重みのリストを空白区切りの文字列に連結します。
     *
     * @param weights ゲイン重み（{@code 0.0} 以上推奨）
     * @return 例：{@code "1.0 0.7 0.5"}
     */
    private static String joinWeights(final List<Double> weights) {
        StringJoiner sj = new StringJoiner(" ");
        for (Double d : weights) {
            sj.add(String.valueOf(d));
        }
        return sj.toString();
    }

    /**
     * フィルタグラフ文字列内の {@code [inX]} の出現数を数えて入力本数を推定します。
     *
     * @param filterDesc フィルタグラフ文字列
     * @return 入力本数（最低 1）
     */
    private static int countInputsInFilter(final String filterDesc) {
        int count = 0;
        int idx = 0;
        while (true) {
            int pos = filterDesc.indexOf("[in", idx);
            if (pos < 0) break;
            count++;
            idx = pos + 3;
        }
        return Math.max(count, 1);
    }

    /**
     * すべての入力が終了したか判定します。
     *
     * @param ended 各入力の終了フラグ
     * @return すべて {@code true} なら {@code true}
     */
    private static boolean allEnded(final boolean[] ended) {
        for (boolean e : ended) {
            if (!e) return false;
        }
        return true;
    }

    /**
     * {@link FFmpegFrameRecorder} を例外を握りつぶして安全に停止・解放します。
     *
     * @param rec 対象レコーダ（{@code null} 可）
     */
    private static void closeQuietly(final FFmpegFrameRecorder rec) {
        if (rec == null) return;
        try { rec.stop(); } catch (Exception ignored) {}
        try { rec.release(); } catch (Exception ignored) {}
    }

    /**
     * {@link FFmpegFrameFilter} を例外を握りつぶして安全に停止・解放します。
     *
     * @param filter 対象フィルタ（{@code null} 可）
     */
    private static void closeQuietly(final FFmpegFrameFilter filter) {
        if (filter == null) return;
        try { filter.stop(); } catch (Exception ignored) {}
        try { filter.close(); } catch (Exception ignored) {}
    }

    /**
     * {@link FFmpegFrameGrabber} の一覧を例外を握りつぶして安全に停止・解放します。
     *
     * @param gs グラバー一覧（{@code null} 可）
     */
    private static void closeQuietly(final List<FFmpegFrameGrabber> gs) {
        if (gs == null) return;
        for (FFmpegFrameGrabber g : gs) {
            if (g == null) continue;
            try { g.stop(); } catch (Exception ignored) {}
            try { g.release(); } catch (Exception ignored) {}
        }
    }

    // ====================================================================================
    // Demo
    // ====================================================================================

    /**
     * 実行例：a.wav（基準）, b.m4a, c.mp3 をミックスして mixed.flac を作成します。
     *
     * @param args 未使用
     * @throws Exception 実行に失敗した場合
     */
    public static void main(String[] args) throws Exception {
        List<String> ins = List.of("a.wav", "b.m4a", "c.mp3"); // 先頭（a.wav）を基準に SR/CH を決定
        List<Double> weights = null; // 例: List.of(1.0, 0.7, 0.5);

        mixToFlacByFirst(
                ins,
                "mixed.flac",
                weights,
                DurationMode.LONGEST, // 最長に合わせる
                true,                  // normalize でクリップ抑止
                8                      // FLAC 圧縮レベル（0〜12）
        );
        System.out.println("done: mixed.flac");
    }
}
