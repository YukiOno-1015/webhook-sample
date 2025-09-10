
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 複数の音声ファイル（例: M4A, MP3, WAV など）を読み込み、
 * Java 側で PCM サンプルを加算してミックスし、FLAC ファイルに出力するメインクラス.
 *
 * <p>
 * 特徴:
 * <ul>
 *   <li>FFmpeg の amix フィルタを使わず、Java 側でサンプルを直接加算する.</li>
 *   <li>チャンネル数・サンプリングレートは最初の入力ファイルを基準に統一.</li>
 *   <li>安全のためサンプル加算後に short 範囲にクリップ.</li>
 * </ul>
 */
public class Main {

	private static final Logger log = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		if (args.length < 3) {
			System.err.println("Usage: java -jar app.jar --out /path/to/mixed.flac in1.m4a in2.m4a [...]");
			System.exit(2);
		}

		// 出力パスと入力ファイル群を解析
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
			mixFlacByFirst_ManualPCM(inputs, outPath);
		} catch (Exception e) {
			log.error("Fatal error in mixing process", e);
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * 指定された複数音声ファイルを読み込み、PCM レベルで加算ミックスして
	 * 単一の FLAC ファイルとして出力する.
	 *
	 * @param inputs  入力音声ファイル群
	 * @param outPath 出力 FLAC ファイルパス
	 * @throws Exception 異常終了時
	 */
	public static void mixFlacByFirst_ManualPCM(List<String> inputs, String outPath) throws Exception {
		Objects.requireNonNull(inputs);
		Objects.requireNonNull(outPath);
		log.info("Inputs: {}", inputs);
		log.info("Output: {}", outPath);

		List<FFmpegFrameGrabber> grabbers = new ArrayList<>();
		try {
			// 入力 grabber をオープン
			for (String in : inputs) {
				FFmpegFrameGrabber g = new FFmpegFrameGrabber(in);
				g.start();
				grabbers.add(g);
			}

			// 基準フォーマットを決定（最初の入力ファイル）
			FFmpegFrameGrabber g0 = grabbers.get(0);
			int sr = g0.getSampleRate() > 0 ? g0.getSampleRate() : 44100;
			int ch = g0.getAudioChannels() > 0 ? g0.getAudioChannels() : 2;
			if (ch != 1 && ch != 2) {
				ch = 2; // 1 or 2 に限定
			}
			log.info("Reference: {} Hz, {} ch", sr, ch);

			// 出力レコーダ（FLAC）
			try (FFmpegFrameRecorder rec = new FFmpegFrameRecorder(outPath, ch)) {
				rec.setFormat("flac");
				rec.setAudioCodec(avcodec.AV_CODEC_ID_FLAC);
				rec.setSampleRate(sr);
				rec.setAudioChannels(ch);
				rec.start();

				// メイン処理ループ
				processAndMixLoop(grabbers, rec, sr, ch);
			}

			log.info("Done: {}", outPath);
		} finally {
			// grabber のクリーンアップ
			for (FFmpegFrameGrabber g : grabbers) {
				safeClose(g, "grabber");
			}
		}
	}

	/**
	 * 入力 grabber 群からサンプルを順次取得し、Java 側で PCM を加算して
	 * 出力レコーダに書き込むループ.
	 *
	 * @param grabbers 入力 grabber 群
	 * @param rec      出力レコーダ
	 * @param sr       基準サンプルレート
	 * @param ch       基準チャンネル数
	 * @throws Exception 読み込み/書き込みエラー時
	 */
	private static void processAndMixLoop(List<FFmpegFrameGrabber> grabbers,
			FFmpegFrameRecorder rec,
			int sr,
			int ch) throws Exception {
		boolean[] ended = new boolean[grabbers.size()];

		while (true) {
			// 各入力から1フレームずつ取得
			ShortBuffer[] bufs = new ShortBuffer[grabbers.size()];
			int maxLen = 0;
			for (int i = 0; i < grabbers.size(); i++) {
				if (ended[i]) {
					continue;
				}
				Frame f = grabbers.get(i).grabSamples();
				if (f == null) {
					ended[i] = true;
					continue;
				}
				if (f.samples != null && f.samples[0] instanceof ShortBuffer) {
					bufs[i] = (ShortBuffer) f.samples[0];
					maxLen = Math.max(maxLen, bufs[i].remaining());
				}
			}

			if (maxLen == 0) {
				break; // 全て終了
			}

			// ミックス処理
			ShortBuffer mixed = mixBlock(bufs, maxLen);

			// 書き込み
			rec.recordSamples(sr, ch, mixed);
		}
	}

	/**
	 * PCM サンプル群を 1 ブロック分加算平均して返す.
	 * @param bufs   入力 ShortBuffer 群
	 * @param length 出力長（サンプル数）
	 * @return ミックス後の ShortBuffer
	 */
	private static ShortBuffer mixBlock(ShortBuffer[] bufs, int length) {
		ShortBuffer mixed = ShortBuffer.allocate(length);

		for (int j = 0; j < length; j++) {
			int sum = 0, count = 0;
			for (ShortBuffer sb : bufs) {
				if (sb != null && sb.hasRemaining()) {
					sum += sb.get();
					count++;
				}
			}
			short val = 0;
			if (count > 0) {
				int avg = sum / count;
				// short の範囲にクリップ
				if (avg > Short.MAX_VALUE) {
					avg = Short.MAX_VALUE;
				}
				if (avg < Short.MIN_VALUE) {
					avg = Short.MIN_VALUE;
				}
				val = (short) avg;
			}
			mixed.put(val);
		}
		mixed.flip();
		return mixed;
	}

	/**
	 * grabber を安全にクローズ.
	 */
	private static void safeClose(FFmpegFrameGrabber g, String name) {
		if (g == null) {
			return;
		}
		try {
			g.stop();
		} catch (Exception e) {
			log.warn("Error stopping {}: {}", name, e.getMessage(), e);
		}
		try {
			g.release();
		} catch (Exception e) {
			log.warn("Error releasing {}: {}", name, e.getMessage(), e);
		}
	}
}
