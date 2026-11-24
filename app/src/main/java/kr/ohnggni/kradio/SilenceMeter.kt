package kr.ohnggni.kradio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 방송 소리 크기만 재는 통과형 음성 처리기 (소리는 그대로 내보냄).
 * 플레이어 음량 적용 전 단계라 앱·폰 음량과 상관없이 방송 원래 크기를 잼.
 * 마지막으로 소리가 난 시각을 기억 → 오래 조용하면 "소리 없는 방송"(DJ가 송출을 멈춘 개인 방송 등)
 */
@UnstableApi
class SilenceMeter : BaseAudioProcessor() {
    /** 약 -70dBFS (공중파 점검 서버의 무음 기준과 같음). 16비트 표본 크기로 32768 × 10^(-70/20) ≈ 10 */
    private val threshold = 10

    @Volatile private var lastLoudAt = System.currentTimeMillis()

    /** 채널이 바뀌거나 재생을 새로 시작할 때: 지금부터 다시 잼 */
    fun restartMeasure() { lastLoudAt = System.currentTimeMillis() }

    /** 마지막으로 소리가 난 뒤 지난 시간(ms) */
    fun quietFor(): Long = System.currentTimeMillis() - lastLoudAt

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET   // 16비트가 아니면 끼지 않음 (재생엔 영향 없음)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        // 재기: 표본을 건너뛰며(8개 중 1개) 훑어서 하나라도 기준을 넘으면 "소리 있음"
        val start = inputBuffer.position()
        var loud = false
        var i = start
        val end = inputBuffer.limit() - 1
        while (i < end) {
            val sample = (inputBuffer.get(i).toInt() and 0xFF) or (inputBuffer.get(i + 1).toInt() shl 8)
            if (abs(sample.toShort().toInt()) > threshold) { loud = true; break }
            i += 16
        }
        if (loud) lastLoudAt = System.currentTimeMillis()
        // 그대로 내보냄
        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }
}
