package com.onestudio.animazoom

import android.graphics.*
import java.util.*

class MakeshiftWordsManager {
    // Chave Geral: Liga/Desliga o 'Sequestro de Sinal'
    var isSequestroActive: Boolean = true

    private val random = Random()
    private val languages = listOf("PT", "EN", "ES", "FR", "IT", "DE", "RU")
    
    // Listas de palavras (Placeholders para 100 palavras por idioma)
    private val wordDatabase = mapOf(
        "PT" to listOf("VERDADE", "FALHA", "SISTEMA", "REALIDADE", "VISÃO", "FANTASMA", "CÓDIGO", "TEMPO", "MEMÓRIA", "SILÊNCIO"),
        "EN" to listOf("TRUTH", "GLITCH", "SYSTEM", "REALITY", "VISION", "GHOST", "CODE", "TIME", "MEMORY", "SILENCE"),
        "ES" to listOf("VERDAD", "FALLO", "SISTEMA", "REALIDAD", "VISIÓN", "FANTASMA", "CÓDIGO", "TIEMPO", "MEMORIA", "SILENCIO"),
        "FR" to listOf("VÉRITÉ", "ERREUR", "SYSTÈME", "RÉALITÉ", "VISION", "FANTÔME", "CODE", "TEMPS", "MÉMOIRE", "SILENCE"),
        "IT" to listOf("VERITÀ", "ERRORE", "SISTEMA", "REALTÀ", "VISIONE", "FANTASMA", "CODICE", "TEMPO", "MEMORIA", "SILENZIO"),
        "DE" to listOf("WAHRHEIT", "FEHLER", "SYSTEM", "REALITÄT", "VISION", "GEIST", "CODE", "ZEIT", "SPEICHER", "STILLE"),
        "RU" to listOf("ИСТИНА", "СБОЙ", "СИСТЕМА", "РЕАЛЬНОСТЬ", "ВИДЕНИЕ", "ПРИЗРАК", "КОД", "ВРЕМЯ", "ПАМЯТЬ", "ТИШИНА")
    )

    private val wordPaint = Paint().apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private var currentWord: String? = null
    private var lastSwitchTime: Long = 0
    private var posX: Float = 0f
    private var posY: Float = 0f
    private val displayDurationMs = 200 // Milissegundos que a palavra fica na tela
    private val intervalMs = 2500 // Intervalo entre aparições

    fun drawSequestro(canvas: Canvas, w: Int, h: Int) {
        if (!isSequestroActive) return

        val currentTime = System.currentTimeMillis()
        
        // Lógica de "Vrap!": Aparece do nada, fica estática e some
        if (currentTime - lastSwitchTime > intervalMs) {
            // Sorteia nova palavra e posição
            val lang = languages[random.nextInt(languages.size)]
            val list = wordDatabase[lang]!!
            currentWord = list[random.nextInt(list.size)]
            
            // Margem de segurança de 10%
            val paddingX = w * 0.15f
            val paddingY = h * 0.15f
            posX = paddingX + random.nextFloat() * (w - 2 * paddingX)
            posY = paddingY + random.nextFloat() * (h - 2 * paddingY)
            
            lastSwitchTime = currentTime
        }

        // Se estiver dentro do tempo de exibição, desenha
        if (currentWord != null && currentTime - lastSwitchTime < displayDurationMs) {
            // Reduzido drasticamente para se misturar às letras do fundo
            wordPaint.textSize = w * 0.012f 
            wordPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            
            // Estética Bicolor (1/2 Fita: Topo Preto, Baixo Vermelho)
            val textHeight = wordPaint.fontMetrics.descent - wordPaint.fontMetrics.ascent
            val shader = LinearGradient(
                0f, posY - textHeight / 2, 
                0f, posY + textHeight / 2,
                intArrayOf(Color.BLACK, Color.BLACK, Color.RED, Color.RED),
                floatArrayOf(0f, 0.5f, 0.51f, 1f), // Corte seco no meio
                Shader.TileMode.CLAMP
            )
            wordPaint.shader = shader
            
            canvas.drawText(currentWord!!, posX, posY, wordPaint)
        }
    }
}
