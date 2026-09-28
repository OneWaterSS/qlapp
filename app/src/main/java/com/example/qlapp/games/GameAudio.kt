package com.example.qlapp.games

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.*

/** Short original synthesized sounds; no downloads and no media permissions. */
class GameAudio(context:Context) {
    private val pool=SoundPool.Builder().setMaxStreams(16).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build()
    private val samples=mutableMapOf<String,Int>()
    private val ready=mutableSetOf<Int>()
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile var enabled=true
    private var closed=false
    init {
        pool.setOnLoadCompleteListener { _,id,status -> synchronized(this){if(status==0)ready.add(id)} }
        // 音色库每变一次就要换目录名：缓存的 wav 按 note0..noteN 命名，索引含义变了旧缓存就是错的。
        val directory=File(context.cacheDir,"arcade-audio-v3")
        worker.execute {
            runCatching { File(context.cacheDir,"arcade-audio-v2").deleteRecursively() }
            directory.mkdirs()
            val sounds=Cue.entries.filter{it!=Cue.NOTE}.map{it.name to it}.toMutableList()
            repeat(PianoScale.size){sounds.add("note$it" to Cue.NOTE)}
            for((key,cue) in sounds){
                synchronized(this){if(closed)return@execute}
                try {
                    val file=File(directory,"$key.wav")
                    if(!file.exists())file.writeBytes(synthesize(cue,if(cue==Cue.NOTE)key.removePrefix("note").toInt() else 0))
                    synchronized(this){if(!closed)samples[key]=pool.load(file.path,1)}
                } catch(_:Exception){ /* Sound failure never interrupts gameplay. */ }
            }
        }
    }
    @Synchronized fun play(event:GameEvent){
        if(!enabled||closed)return
        val id=samples[if(event.cue==Cue.NOTE)"note${event.note.coerceIn(0,PianoScale.size-1)}" else event.cue.name]?:return
        if(id !in ready)return
        val volume=when(event.cue){
            Cue.SHOT->.09f
            Cue.NOTE->.55f
            Cue.PLACE->.52f
            Cue.PLACE_RIVAL->.36f
            Cue.CHIME->.40f
            Cue.GOMOKU_WIN->.55f
            Cue.GOMOKU_LOSE->.48f
            Cue.GOMOKU_DRAW->.46f
            else->.35f
        }
        pool.play(id,volume,volume,1,0,1f)
    }
    @Synchronized fun pause(){if(!closed)pool.autoPause()}
    @Synchronized fun close(){if(closed)return;closed=true;worker.shutdownNow();pool.release()}

    private fun synthesize(cue:Cue,note:Int):ByteArray {
        val rate=32000
        val seconds=when(cue){
            Cue.NOTE->.95;Cue.EXPLOSION->.3;Cue.WIN->.7;Cue.LOSE->.5
            Cue.PLACE->.15;Cue.PLACE_RIVAL->.16
            Cue.CHIME->.62
            Cue.GOMOKU_WIN->1.45;Cue.GOMOKU_LOSE->1.55;Cue.GOMOKU_DRAW->1.00
            else->.16
        }
        val count=(rate*seconds).toInt()
        val buffer=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray());buffer.putInt(36+count*2);buffer.put("WAVEfmt ".toByteArray());buffer.putInt(16);buffer.putShort(1);buffer.putShort(1);buffer.putInt(rate);buffer.putInt(rate*2);buffer.putShort(2);buffer.putShort(16);buffer.put("data".toByteArray());buffer.putInt(count*2)
        val frequency=when(cue){
            Cue.NOTE->261.63*2.0.pow(PianoScale[note.coerceIn(0,PianoScale.size-1)]/12.0)
            Cue.PICKUP->880.0;Cue.JUMP->420.0;Cue.SHOT->900.0;Cue.WIN->523.25;Cue.LOSE->190.0;else->100.0
        }
        for(i in 0 until count){
            val t=i.toDouble()/rate
            val sample=when(cue){
                Cue.NOTE->{
                    // 钢琴：极快起振 + 双指数衰减，叠加谐波、轻微失谐和一点击弦瞬态。
                    val w=2*PI*frequency
                    val env=min(1.0,t/.0035)*(.74*exp(-t*3.4)+.26*exp(-t*.9))*(1.0-t/seconds)
                    val hammer=if(t<.005)sin(i*2.31)*exp(-t/.0016)*.30 else 0.0
                    (sin(w*t)+.44*sin(w*2*t)+.24*sin(w*3*t)+.13*sin(w*4*t)+.07*sin(w*5*t)+.50*sin(w*1.002*t)+hammer)*env*.46
                }
                // 棋子落在木盘上：一下极短的击打瞬态，接快速衰减的木质泛音。
                // 对方那一手压低到纯五度附近、也轻一些，不抬头看屏就知道是谁落的子。
                Cue.PLACE,Cue.PLACE_RIVAL->{
                    val f=if(cue==Cue.PLACE)628.0 else 424.0
                    val w=2*PI*f
                    val env=min(1.0,t/.0012)*exp(-t*33)
                    val knock=sin(i*2.17)*sin(i*.83)*exp(-t/.0032)
                    (sin(w*t)+.46*sin(w*2.4*t)+.20*sin(w*4.3*t))*env*.55+knock*.28
                }
                // 提示音：两声清越的磬（徵 → 宫），用来叫「有邀请 / 开局了」。
                Cue.CHIME->bell(783.99,t,5.2)*.46+bell(1046.50,t-.17,4.6)*.36
                // 胜：五声音阶上行，古琴拨弦。
                Cue.GOMOKU_WIN->PENTATONIC_UP.mapIndexed{k,f->pluck(f,t-k*.115,3.1)*(1.0-k*.07)}.sum()*.30
                // 负：同一音阶下行，音色更暗、间隔更慢。
                Cue.GOMOKU_LOSE->PENTATONIC_DOWN.mapIndexed{k,f->pluck(f,t-k*.17,2.2)*(1.0-k*.06)}.sum()*.30
                // 和棋：同一个音平着敲两下，不带倾向。
                Cue.GOMOKU_DRAW->pluck(659.25,t,2.9)*.38+pluck(659.25,t-.28,2.9)*.30
                else->{
                    val f=when(cue){Cue.SHOT->frequency*(1-t*3);Cue.JUMP->frequency*(1+t*3);else->frequency}
                    val envelope=min(1.0,t/.007)*exp(-t/seconds*5)*(1-t/seconds)
                    val grit=if(cue==Cue.EXPLOSION||cue==Cue.HURT)sin(i*1.715)*sin(i*.937)*1.2 else 0.0
                    (sin(2*PI*f*t)+.28*sin(4*PI*f*t)+.12*sin(6*PI*f*t)+grit)*envelope
                }
            }
            buffer.putShort((sample*15000).coerceIn(-32767.0,32767.0).toInt().toShort())
        }
        return buffer.array()
    }

    /** 古琴 / 筝式拨弦：极快起振 + 指数衰减 + 少量整数泛音。t 为负表示这个音还没起。 */
    private fun pluck(f:Double,t:Double,decay:Double):Double {
        if(t<0)return 0.0
        val w=2*PI*f
        val env=min(1.0,t/.0025)*exp(-t*decay)
        return (sin(w*t)+.34*sin(w*2*t)+.15*sin(w*3*t)+.06*sin(w*4*t))*env
    }

    /** 磬 / 编钟：泛音刻意不谐和（2.76 / 5.40 倍），尾音拖得长。 */
    private fun bell(f:Double,t:Double,decay:Double):Double {
        if(t<0)return 0.0
        val w=2*PI*f
        val env=min(1.0,t/.0015)*exp(-t*decay)
        return (sin(w*t)+.55*sin(w*2.76*t)+.28*sin(w*5.40*t))*env
    }

    private companion object {
        /** 五声音阶（宫 商 角 徵 羽）上行，胜局用。 */
        val PENTATONIC_UP=doubleArrayOf(523.25,587.33,659.25,783.99,880.00,1046.50)

        /** 同一音阶下行收束到低音宫，败局用。 */
        val PENTATONIC_DOWN=doubleArrayOf(880.00,783.99,659.25,587.33,523.25,392.00)
    }
}
