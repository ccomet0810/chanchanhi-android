package dev.ccomet.chanchanhi04.ui

/** Irregular syllable peaks and short silences sampled into the same RMS history as real input. */
object IntroSpeechEnvelope{
    private val syllables=listOf(0f,.12f,.48f,.76f,.31f,.15f,.54f,.91f,.38f,.08f,0f,.23f,.64f,.42f,.81f,.29f,.07f,0f,.36f,.73f,.96f,.47f,.18f,.59f,.83f,.33f,.09f,0f,.25f,.62f,.39f,.79f,.44f,.16f,.51f,.26f,0f)
    fun level(ms:Long):Float{
        if(ms<0||ms>=3600)return 0f
        val p=ms/100f;val i=p.toInt().coerceAtMost(syllables.lastIndex-1)
        return syllables[i]+(syllables[i+1]-syllables[i])*(p-i)
    }
    fun history(ms:Long,count:Int=22)=List(count){i->level(ms-(count-1-i)*45L)}
}
