package com.example.texttospeechspeechtotextapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import android.Manifest
import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.ImageView
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import kotlinx.coroutines.*
import retrofit2.http.Query
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import coil.load
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.TextAlignment

private val RECORD_AUDIO_PERMISSION_CODE = 1

@Serializable
data class InternalJson(
    val text: String,
    val image: String
//    val sources: List<String>
)

class Entry(inputIn:String, outputIn:String, imgIn:String) {
    val input: String = inputIn
    val output: String = outputIn
    val img: String = imgIn
}

class MainActivity : AppCompatActivity() {
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var inputText: TextView
    private lateinit var instructionText: TextView
    private lateinit var outputText: TextView
    private lateinit var overlayView: View
    private lateinit var speechRecognizer: SpeechRecognizer
    private val listenKeyword = "buddy"
    private val screenOnKeyword = "wake"
    private val screenOffKeyword = "sleep"
    private val clearScreenKeyword = "clear"
    private val doneKeyword = "cancel"
    private val nextKeyword = "next"
    private val backKeyword = "back"
    private val notesKeyword = "notes"
    private var notes = false


    private  var instructionInProgress = false
    private var countingJob: Job? = null
    private var apiJob: Job? = null

    private var myMutableList = mutableListOf<Entry>()
    private var currPage = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        inputText = findViewById(R.id.inputText)

        outputText = findViewById(R.id.outputText)

        instructionText = findViewById(R.id.instructionText)

        overlayView = findViewById<View>(R.id.overlay)

        // Initialize TextToSpeech
//        textToSpeech = TextToSpeech(this) { status ->
//            if (status == TextToSpeech.SUCCESS) {
//                val result = textToSpeech.setLanguage(Locale.getDefault())
//                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
//                    Toast.makeText(this, "Language is not supported", Toast.LENGTH_LONG).show()
//                }
//            }
//        }
        hideAllUI()
        showAllUI()

        // Initialize continuous speech recognition
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        checkAndRequestAudioPermission()

        countingFunc("🔇  Waiting for audio")

    }

    override fun onDestroy() {
        super.onDestroy()

        countingJob?.cancel()

        // Stop Speech Recognizer
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }

        // Shutdown Text-to-Speech
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }

        // Clear View and Memory
        window.decorView.rootView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Kill the App Process Completely
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {

            // Permission not granted, request it
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        } else {
            // Permission already granted, start listening
            startKeywordListening()
        }
    }

    fun addContentToPDF(input: String) {
        // Specify your desired directory path
        val directory = "/Internal storage/Download/"

        val file = File(directory, "notes.pdf")

        if (!file.exists()) {
            file.createNewFile()
        }

        // Create a PdfReader for the existing PDF
        val pdfReader = PdfReader(file)

        // Create a PdfWriter to write the new content
        val pdfWriter = PdfWriter(file)

        // Create a PdfDocument for the existing file and the writer
        val pdfDocument = PdfDocument(pdfReader, pdfWriter)

        // Create a Document instance for adding content
        val document = Document(pdfDocument)

        // Add new content (e.g., a paragraph) to the PDF
        document.add(Paragraph(input + "\n")
            .setTextAlignment(TextAlignment.CENTER))

        // Close the document
        document.close()
    }

    private fun countingFunc(input:String) {
        if (instructionInProgress) return
        countingJob?.cancel()
        countingJob = GlobalScope.launch(Dispatchers.Main) {
            while (true){
                var instr = input
                for (i in 1..3) {
                    delay(500) // Delay for 1 second
                    instr = "$instr."
                    instructionText.text = instr
                }
            }
        }
    }

    private fun printMessage(message: String) {
        inputText.append(message)
        inputText.append(" ")
    }

    private fun hideAllUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hideSystemUIForApi30AndAbove()
        } else {
            hideSystemUIForOlderApis()
        }

        // Hide everything by making the overlay visible
        overlayView.visibility = View.VISIBLE

    }

    private fun showAllUI(){
        overlayView.visibility = View.GONE
    }

    @SuppressLint("NewApi")
    private fun hideSystemUIForApi30AndAbove() {
        window.insetsController?.hide(
            android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
        )

        // Make the content area fully immersive
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // For older Android versions (Pre-API 30)
    private fun hideSystemUIForOlderApis() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
    }
    
    private fun instructionStart(input: Boolean){
        instructionInProgress = input
        if (instructionInProgress) {
            countingJob?.cancel()
            instructionText.text = ("⚙\uFE0F  Processing  ⚙\uFE0F ")
        }
        else {
            countingFunc("🔇  Waiting for audio")
        }
    }

    private fun checkListening(input: Boolean){
        if (input) {
            countingJob?.cancel()
            instructionText.text = ("\uD83C\uDFA7  Listening ")
        }
        else {
            countingFunc("🔇  Waiting for audio")
        }
    }

    private fun consider (result: String){
        if (result.contains(listenKeyword, ignoreCase = true)) {
            if (instructionInProgress) return
            instructionStart(true)
            startFullSpeechRecognition()
        }
        else if (result.contains(screenOffKeyword, ignoreCase = true)) {
            instructionStart(true)
            hideAllUI()
            stopListening()
            instructionStart(false)
        }
        else if (result.contains(doneKeyword, ignoreCase = true)){
            instructionStart(true)
            stopListening()
            cancelApiCall()
            inputText.text = "Input cancelled"
            defineImage(null.toString())
            instructionStart(false)
        }
        else if (result.contains(screenOnKeyword, ignoreCase = true)){
            instructionStart(true)
            showAllUI()
            instructionStart(false)
        }
        else if (result.contains(nextKeyword, ignoreCase = true)){
            instructionStart(true)
            nextChat()
            instructionStart(false)
        }
        else if (result.contains(backKeyword, ignoreCase = true)){
            instructionStart(true)
            backChat()
            instructionStart(false)
        }
//        else if (result.contains(notesKeyword, ignoreCase = true)){
//            instructionStart(true)
//            addNotes()
//            instructionStart(false)
//        }
//        else if (result.contains(clearScreenKeyword, ignoreCase = true)){
//            instructionStart(true)
//            clearScreen()
//            instructionStart(false)
//        }

    }

    private fun backChat() {
        currPage--
        changeChat()
    }

    private fun nextChat() {
        currPage++
        changeChat()
        
    }
    
    private fun changeChat(){
        if (currPage < 1) {
            currPage = 1
            return
        }
        else if (currPage > myMutableList.size -1){
            currPage = myMutableList.size -1
            return
        }
        inputText.text = "Input heard: " + myMutableList[currPage-1].input
        outputText.text = myMutableList[currPage-1].output

        if (myMutableList[currPage-1].img.isNotEmpty()) defineImage(myMutableList[currPage-1].img)
    }

    private fun clearScreen(){
        outputText.text = ""
        inputText.text = "Input heard: "
    }

    private fun stopListening() {
        // If speech recognition is active, stop/cancel it
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.stopListening()
            speechRecognizer.cancel()
        }

        // Bring app back to the foreground to override the Google UI
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        startKeywordListening()
    }


    private fun startKeywordListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            flags = Intent.FLAG_ACTIVITY_NO_HISTORY // Ensures it is cleared once dismissed
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onBeginningOfSpeech() { if (!instructionInProgress) checkListening(true)  }

            @SuppressLint("SetTextI18n")
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (result in matches) {
                        consider(result)
                    }
                }

                startKeywordListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {

            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    startKeywordListening() // Restart if it stops
                }
            }

            override fun onEndOfSpeech() {
                if (!instructionInProgress) checkListening(false)
               startKeywordListening()
            }


//            override fun onPartialResults(partialResults: Bundle?) {
//                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
//                if (matches != null) {
//                    for (result in matches) {
//                        consider(result)
//                    }
//                }
//
//                startKeywordListening()
//            }

            override fun onRmsChanged(rmsdB: Float) {  }


            override fun onEvent(eventType: Int, params: Bundle?) {}
        })



        speechRecognizer.startListening(intent)
    }

    private fun startFullSpeechRecognition() {
        showAllUI()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 50)
        }

        result.launch(intent)
    }

    private fun addNotes() {
        showAllUI()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 50)
        }

        notes = true

        result.launch(intent)
    }

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)  // Set the connection timeout
        .readTimeout(30, TimeUnit.SECONDS)     // Set the read timeout
        .writeTimeout(30, TimeUnit.SECONDS)    // Set the write timeout
        .build()

    val retrofit = Retrofit.Builder()
        .baseUrl("https://buddy.grantoyt.workers.dev/")
        .client(okHttpClient)
        .addConverterFactory(
            Json.asConverterFactory(
                "application/json; charset=UTF8".toMediaType()))
        .build()

    // Create an instance of your API service
    private val apiService = retrofit.create(ApiService::class.java)

    interface ApiService {

        @GET("?")
        suspend fun getData(
            @Query("query") userInput: String,
        ): InternalJson
    }

    private fun encodeQuery(query: String): String {
        return URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
    }

    private fun cancelApiCall() {
        apiJob?.cancel()
    }

    fun makeApiCall(userInput: String) {
        cancelApiCall()
        apiJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val output = apiService.getData(userInput)
                outputText.text = output.text

                if (output.image.isNotEmpty()) {
                    Log.d("image", "image is attempting render")
                    defineImage(output.image)
                }

                currPage++
                myMutableList.add(Entry(userInput, output.text, output.image))
            } catch (e: CancellationException) {
                Log.d("API_CALL", "API request was cancelled")
            } catch (e: Exception) {
                Log.e("API_CALL", "API request failed: ${e.message}")

                outputText.text= "API Error- Try again!"
            }
            instructionStart(false)
            countingFunc("🔇  Waiting for audio")
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    private val result = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: androidx.activity.result.ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as ArrayList<String>
            if (!results[0].contains(doneKeyword)) {
                inputText.setText("Input heard: " + results[0])
                instructionInProgress = false
                countingFunc("\uD83D\uDD52  Waiting for response")
                instructionInProgress = true
                if (!notes) makeApiCall(results[0])
                else {
                    notes = false
                    addContentToPDF(results[0])
                }

            }
            else {
                inputText.setText("Input cancelled")
                defineImage(null.toString())
            }


//            val send = InternalJson(results[0])
//
//            // Serialize to JSON string
//            val jsonString = Json.encodeToString(send)


            //HERE

        }
        startKeywordListening() // Restart listening after recognition
    }

    private fun defineImage(imageURLIn: String){
        Log.d("ImageLoad", "Attempting to load image: $imageURLIn")
        val imageView: ImageView = findViewById(R.id.imageView)

        imageView.load(imageURLIn) {

            crossfade(true)
            listener(onError = { _, throwable ->
                Log.e("CoilError", "Error loading image: ${imageURLIn}")
            })
        }
    }

}
