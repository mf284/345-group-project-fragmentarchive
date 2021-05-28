package com.poemgen.mockspire.model

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.poemgen.mockspire.poemgenerator.ai.MockGenerator
import com.poemgen.mockspire.poemgenerator.record.Garden
import com.poemgen.mockspire.poemgenerator.record.Poem

class PoemMainViewModel : ViewModel() {

    private val _title = MutableLiveData<String>()
    val title: LiveData<String> = _title

    private val _poemText = MutableLiveData<SpannableString>()
    val poemText: LiveData<SpannableString> = _poemText

    private val _readyForPrompt = MutableLiveData<Boolean>()
    val readyForPrompt: LiveData<Boolean> = _readyForPrompt

    private val poemGenerator = MockGenerator()


    init {
        setReady(true)
    }



    fun submitPrompt(prompt: String) {
        poemGenerator.submitPrompt(prompt)
        var content = poemGenerator.getPoem()

        var newPoem = Poem(prompt, content)

        // Add to log
        Garden.seeds.add(newPoem)

        _poemText.postValue(generateSpannables(poemGenerator.getPoem()))

    }

    fun setReady(ready: Boolean) {
        _readyForPrompt.value = ready
    }

    fun generateSpannables(text: String): SpannableString {
        val outputSpannable = SpannableString(text)

        val tempClickable = object : ClickableSpan() {
            override fun onClick(widget: View) {
                Log.d("Mock", "heyoo")
            }

        }
        outputSpannable.setSpan(tempClickable, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        return outputSpannable
    }


}