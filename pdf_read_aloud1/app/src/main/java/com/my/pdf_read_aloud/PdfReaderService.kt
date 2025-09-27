package com.my.pdf_read_aloud

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.ui.text.toLowerCase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class PdfReaderService(private val context: Context) {
    
    private var textToSpeech: TextToSpeech? = null
    private var pdfDocument: PDDocument? = null
    private var currentText: String = ""
    private var currentPageNumber: Int = 1
    private var totalPages: Int = 0
    private var isInitialized = false
    private var excludedTexts: List<String> = emptyList()
    private var continuousReading = true  // Flag to control continuous reading behavior
    
    private val _state = MutableStateFlow<ReaderState>(ReaderState.Idle)
    val state: StateFlow<ReaderState> = _state.asStateFlow()
    
    init {
        PDFBoxResourceLoader.init(context)
        initTextToSpeech()
    }
    
    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    _state.value = ReaderState.Error("Language not supported")
                } else {
                    isInitialized = true
                    setupTtsListener()
                }
            } else {
                _state.value = ReaderState.Error("TextToSpeech initialization failed")
            }
        }
    }
    
    private fun setupTtsListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.value = ReaderState.Reading(currentPageNumber, totalPages)
            }
            
            override fun onDone(utteranceId: String?) {
                if (_state.value is ReaderState.Reading) {
                    // Check if we should continue to the next page
                    if (continuousReading && currentPageNumber < totalPages) {
                        // Automatically read the next page
                        readPage(currentPageNumber + 1)
                    } else {
                        _state.value = ReaderState.Paused(currentPageNumber, totalPages)
                    }
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = ReaderState.Error("Error while reading text")
            }
        })
    }
    
    fun openPdfFile(uri: Uri): Boolean {
        try {
            closeCurrentDocument()
            
            val inputStream = context.contentResolver.openInputStream(uri)
            pdfDocument = PDDocument.load(inputStream)
            totalPages = pdfDocument?.numberOfPages ?: 0
            currentPageNumber = 1
            
            if (totalPages > 0) {
                _state.value = ReaderState.Loaded(currentPageNumber, totalPages)
                return true
            } else {
                _state.value = ReaderState.Error("No pages found in PDF")
                return false
            }
        } catch (e: Exception) {
            _state.value = ReaderState.Error("Failed to open PDF: ${e.message}")
            return false
        }
    }
    
    fun readPage(pageNumber: Int = currentPageNumber) {
        if (!isInitialized || pdfDocument == null) {
            _state.value = ReaderState.Error("Reader not initialized or no PDF loaded")
            return
        }
        
        if (pageNumber < 1 || pageNumber > totalPages) {
            _state.value = ReaderState.Error("Invalid page number")
            return
        }
        
        try {
            currentPageNumber = pageNumber
            val stripper = PDFTextStripper()
            stripper.startPage = pageNumber
            stripper.endPage = pageNumber
            
            currentText = stripper.getText(pdfDocument).lowercase()
            
            // Apply excluded text filtering
            var textToRead = currentText
            excludedTexts.forEach { excludedText ->
                if (excludedText.isNotBlank()) {
                    textToRead = textToRead.replace(excludedText.lowercase(), "")
                }
            }
            
            if (textToRead.isBlank()) {
                _state.value = ReaderState.Reading(currentPageNumber, totalPages)
                if (continuousReading && currentPageNumber < totalPages) {
                    // If page is blank and continuous reading is enabled, skip to next page
                    readPage(currentPageNumber + 1)
                } else {
                    //_state.value = ReaderState.Error("No text to read after exclusions")
                }
                return
            }

            _state.value = ReaderState.Reading(currentPageNumber, totalPages)
            stopReading()
            textToSpeech?.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null, "pdf_page_$pageNumber")
        } catch (e: Exception) {
            _state.value = ReaderState.Error("Failed to read page: ${e.message}")
        }
    }
    
    fun pauseReading() {
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
            _state.value = ReaderState.Paused(currentPageNumber, totalPages)
        }
    }
    
    fun resumeReading() {
        if (_state.value is ReaderState.Paused) {
            val textToRead = currentText
            textToSpeech?.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null, "pdf_resume_$currentPageNumber")
            _state.value = ReaderState.Reading(currentPageNumber, totalPages)
        }
    }
    
    fun nextPage() {
        if (currentPageNumber < totalPages) {
            readPage(currentPageNumber + 1)
        }
    }
    
    fun previousPage() {
        if (currentPageNumber > 1) {
            readPage(currentPageNumber - 1)
        }
    }
    
    fun setReadingSpeed(speedFactor: Float) {
        textToSpeech?.setSpeechRate(speedFactor)
    }
    
    fun setExcludedTexts(excludedTexts: List<String>) {
        this.excludedTexts = excludedTexts
    }
    
    fun goToPage(pageNumber: Int) {
        val validPageNumber = min(max(1, pageNumber), totalPages)
        readPage(validPageNumber)
    }
    
    fun setContinuousReading(enabled: Boolean) {
        continuousReading = enabled
    }
    
    fun stopReading() {
        textToSpeech?.stop()
        if (_state.value is ReaderState.Reading) {
            _state.value = ReaderState.Paused(currentPageNumber, totalPages)
        }
    }
    
    fun closeCurrentDocument() {
        stopReading()
        pdfDocument?.close()
        pdfDocument = null
        currentText = ""
        totalPages = 0
        _state.value = ReaderState.Idle
    }
    
    fun shutdown() {
        stopReading()
        textToSpeech?.shutdown()
        textToSpeech = null
        closeCurrentDocument()
    }
    
    sealed class ReaderState {
        object Idle : ReaderState()
        data class Loaded(val currentPage: Int, val totalPages: Int) : ReaderState()
        data class Reading(val currentPage: Int, val totalPages: Int) : ReaderState()
        data class Paused(val currentPage: Int, val totalPages: Int) : ReaderState()
        data class Error(val message: String) : ReaderState()
    }
} 