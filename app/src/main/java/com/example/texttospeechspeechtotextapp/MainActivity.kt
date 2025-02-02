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
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import kotlinx.coroutines.*
import retrofit2.http.Query


private val RECORD_AUDIO_PERMISSION_CODE = 1

@Serializable
data class InternalJson(val instruction: String)

class MainActivity : AppCompatActivity() {
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var editText: TextView
    private lateinit var instructionText: TextView
    private lateinit var outputText: TextView
    private lateinit var overlayView: View
    private lateinit var speechRecognizer: SpeechRecognizer
    private val listenKeyword = "hello"
    private val screenOnKeyword = "wake"
    private val screenOffKeyword = "sleep"
    private val clearScreenKeyword = "clear"
    private val doneKeyword = "exit"
    private  var instructionInProgress = false
    private var countingJob: Job? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        editText = findViewById(R.id.editText)

        outputText = findViewById(R.id.outputText)

        instructionText = findViewById(R.id.instructionText)

        overlayView = findViewById<View>(R.id.overlay)

        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "Language is not supported", Toast.LENGTH_LONG).show()
                }
            }
        }
        hideAllUI()
        showAllUI()

        // Initialize continuous speech recognition
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        checkAndRequestAudioPermission()

        countingFunc()

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

    private fun countingFunc() {
        countingJob?.cancel()
        countingJob = GlobalScope.launch(Dispatchers.Main) {
            while (true){
                var instr = "Waiting for audio"
                for (i in 1..3) {
                    delay(500) // Delay for 1 second
                    instr = "$instr."
                    instructionText.text = instr
                }
            }
        }
    }

    private fun printMessage(message: String) {
        editText.append(message)
        editText.append(" ")
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
            instructionText.text = ("Processing")
        }
        else {
            countingFunc()
        }
    }

    private fun checkListening(input: Boolean){
        if (input) {
            countingJob?.cancel()
            instructionText.text = ("Listening")
        }
        else {
            countingFunc()
        }
    }

    private fun consider (result: String){
        outputText.append(result);
        if (result.contains(listenKeyword, ignoreCase = true)) {
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
            instructionStart(false)
        }
        else if (result.contains(screenOnKeyword, ignoreCase = true)){
            instructionStart(true)
            showAllUI()
            instructionStart(false)
        }
        else if (result.contains(clearScreenKeyword, ignoreCase = true)){
            instructionStart(true)
            clearScreen()
            instructionStart(false)
        }

    }

    private fun clearScreen(){
        outputText.text = ""
        editText.text = "Input heard: "
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

            override fun onBeginningOfSpeech() { checkListening(true)  }

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
                checkListening(false)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 50)
        }

        result.launch(intent)
    }

    val retrofit = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .addConverterFactory(
            Json.asConverterFactory(
                "application/json; charset=UTF8".toMediaType()))
        .build()

    // Create an instance of your API service
    private val apiService = retrofit.create(ApiService::class.java)

    interface ApiService {

        @GET("your-endpoint-here")
        suspend fun getData(
            @Query("query") userInput: String,
        ): InternalJson
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val result = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: androidx.activity.result.ActivityResult ->
        instructionStart(false)
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as ArrayList<String>
            if (!results[0].contains("exit")) {
                editText.setText("Input heard: " + results[0] + "\n Currently contacting server")
//                GlobalScope.launch(Dispatchers.Main) {
//                    val output = apiService.getData(userInput = results[0])
//                    outputText.text = output.instruction
//                }

            }


//            val send = InternalJson(results[0])
//
//            // Serialize to JSON string
//            val jsonString = Json.encodeToString(send)


            //HERE

        }
        startKeywordListening() // Restart listening after recognition
    }

}
