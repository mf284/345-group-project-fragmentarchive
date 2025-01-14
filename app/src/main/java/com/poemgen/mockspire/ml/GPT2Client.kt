package com.poemgen.mockspire.ml

import android.app.Application
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.util.JsonReader
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.databinding.BindingAdapter
import androidx.lifecycle.*
import com.google.android.material.textfield.TextInputEditText
import com.poemgen.mockspire.MainActivity
import com.poemgen.mockspire.R
import com.poemgen.mockspire.poemgenerator.record.Garden
import com.poemgen.mockspire.poemgenerator.record.Poem
import com.poemgen.mockspire.tokenization.GPT2Tokenizer
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.random.Random

private const val SEQUENCE_LENGTH  = 64
private const val VOCAB_SIZE       = 50257
private const val NUM_HEAD         = 12
private const val NUM_LITE_THREADS = 4
private const val MODEL_PATH       = "model.tflite"
private const val VOCAB_PATH       = "gpt2-vocab.json"
private const val MERGES_PATH      = "gpt2-merges.txt"
private const val TAG              = "GPT2Client"

private typealias Predictions = Array<Array<FloatArray>>

enum class GPT2StrategyEnum { GREEDY, TOPK }
data class GPT2Strategy(val strategy: GPT2StrategyEnum, val value: Int = 0)


/**
 * The GPT2Client class loads a GPT2 model and allows the app to generate poems using GPT-2.
 * We used huggingface.co's GPT2 Android Demo as a reference under the Apache License 2.0.
 * subclass of AndroidViewModel.
 * @property prompt The input prompt.
 * @property completion The output generated by the model.
 */
class GPT2Client(application: Application) : AndroidViewModel(application) {
    private val initJob: Job
    private var autocompleteJob: Job? = null
    private lateinit var tokenizer: GPT2Tokenizer
    private lateinit var tflite: Interpreter


    private val _prompt = MutableLiveData<String>("")
    val prompt: LiveData<String> = _prompt


    private val _completion = MutableLiveData("")
    val completion: LiveData<String> = _completion

    var strategy = GPT2Strategy(GPT2StrategyEnum.TOPK, 40)

    lateinit var mainActivity: MainActivity

    init {
        initJob = viewModelScope.launch {
            val encoder  = loadEncoder()
            val decoder  = encoder.entries.associateBy({ it.value }, { it.key })
            val bpeRanks = loadBpeRanks()

            tokenizer = GPT2Tokenizer(encoder, decoder, bpeRanks)
            tflite    = loadModel()

            mainActivity?.enableButtons()
        }
    }

    override fun onCleared() {
        super.onCleared()
        tflite.close()
    }

    fun closeGenerator() {
        tflite.close()
    }

    fun cancelGenerating() {
        autocompleteJob?.cancel()
    }

    /**
     * Launch Autocomplete starts the generation of a new poem using the prompt in the promptfield.
     * While the poem is being generated, all buttons are disabled.
     * Once the poem has been generated it saves the poem to the garden.
     */
    fun launchAutocomplete() {
        autocompleteJob = viewModelScope.launch {
            mainActivity.disableButtons()
            initJob.join()
            autocompleteJob?.cancelAndJoin()
            _completion.value = ""
            generate(_prompt.value!!)

            // Save to volatile memory.
            Garden.seeds.add(Poem(_prompt.value.toString(), completion.value.toString()))
            mainActivity.enableButtons()
        }
    }

    /**
     * Sets the prompt for the GPT2 model.
     * @param prompt The prompt to give the model.
     */
    fun setPrompt(prompt: String) {
        _prompt.value = prompt
        launchAutocomplete()
    }

    /**
     * Generate runs the model and generates a poem.
     * @param text The prompt of the model.
     */
    private suspend fun generate(text: String, nbTokens: Int = 10) = withContext(Dispatchers.Default) {
        val tokens = tokenizer.encode(text)
        repeat (nbTokens) {
            val maxTokens    = tokens.takeLast(SEQUENCE_LENGTH).toIntArray()
            val paddedTokens = maxTokens + IntArray(SEQUENCE_LENGTH - maxTokens.size)
            val inputIds     = Array(1) { paddedTokens }

            val predictions: Predictions = Array(1) { Array(SEQUENCE_LENGTH) { FloatArray(VOCAB_SIZE) } }
            val outputs = mutableMapOf<Int, Any>(0 to predictions)

            tflite.runForMultipleInputsOutputs(arrayOf(inputIds), outputs)
            val outputLogits = predictions[0][maxTokens.size-1]

            val nextToken: Int = when (strategy.strategy) {
                GPT2StrategyEnum.TOPK -> {
                    val filteredLogitsWithIndexes = outputLogits
                            .mapIndexed { index, fl -> (index to fl) }
                            .sortedByDescending { it.second }
                            .take(strategy.value)

                    // Softmax computation on filtered logits
                    val filteredLogits = filteredLogitsWithIndexes.map { it.second }
                    val maxLogitValue  = filteredLogits.max()!!
                    val logitsExp      = filteredLogits.map { exp(it - maxLogitValue) }
                    val sumExp         = logitsExp.sum()
                    val probs          = logitsExp.map { it.div(sumExp) }

                    val logitsIndexes = filteredLogitsWithIndexes.map { it.first }
                    sample(logitsIndexes, probs)
                }
                else -> outputLogits.argmax()
            }

            tokens.add(nextToken)
            val decodedToken = tokenizer.decode(listOf(nextToken))
            _completion.postValue(_completion.value + decodedToken)

            yield()
        }
    }

    /**
     * Loads the GPT2 Model.
     */
    private suspend fun loadModel(): Interpreter = withContext(Dispatchers.IO) {
        val assetFileDescriptor = getApplication<Application>().assets.openFd(MODEL_PATH)
        assetFileDescriptor.use {
            val fileChannel = FileInputStream(assetFileDescriptor.fileDescriptor).channel
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength)

            val opts = Interpreter.Options()
            opts.setNumThreads(NUM_LITE_THREADS)
            return@use Interpreter(modelBuffer, opts)
        }
    }

    /**
     * Loads the GPT2 token encoder to convert a string into tokens for the input of GPT2.
     */
    private suspend fun loadEncoder(): Map<String, Int> = withContext(Dispatchers.IO) {
        hashMapOf<String, Int>().apply {
            val vocabStream = getApplication<Application>().assets.open(VOCAB_PATH)
            vocabStream.use {
                val vocabReader = JsonReader(InputStreamReader(it, "UTF-8"))
                vocabReader.beginObject()
                while (vocabReader.hasNext()) {
                    val key = vocabReader.nextName()
                    val value = vocabReader.nextInt()
                    put(key, value)
                }
                vocabReader.close()
            }
        }
    }

    private suspend fun loadBpeRanks():Map<Pair<String, String>, Int> = withContext(Dispatchers.IO) {
        hashMapOf<Pair<String, String>, Int>().apply {
            val mergesStream = getApplication<Application>().assets.open(MERGES_PATH)
            mergesStream.use { stream ->
                val mergesReader = BufferedReader(InputStreamReader(stream))
                mergesReader.useLines { seq ->
                    seq.drop(1).forEachIndexed { i, s ->
                        val list = s.split(" ")
                        val keyTuple = list[0] to list[1]
                        put(keyTuple, i)
                    }
                }
            }
        }
    }
}

private fun randomIndex(probs: List<Float>): Int {
    val rnd = probs.sum() * Random.nextFloat()
    var acc = 0f

    probs.forEachIndexed { i, fl ->
        acc += fl
        if (rnd < acc) {
            return i
        }
    }

    return probs.size - 1
}

private fun sample(indexes: List<Int>, probs: List<Float>): Int {
    val i = randomIndex(probs)
    return indexes[i]
}

private fun FloatArray.argmax(): Int {
    var bestIndex = 0
    repeat(size) {
        if (this[it] > this[bestIndex]) {
            bestIndex = it
        }
    }

    return bestIndex
}

@BindingAdapter("prompt", "completion")
fun TextView.formatCompletion(prompt: String, completion: String): Unit {
    text = when {
        completion.isEmpty() -> prompt
        else -> {
            val str = SpannableStringBuilder(prompt + completion)
            val bgCompletionColor = ResourcesCompat.getColor(resources, R.color.design_default_color_on_primary, context.theme)
            str.setSpan(android.text.style.BackgroundColorSpan(bgCompletionColor), prompt.length, str.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            str
        }
    }
}
