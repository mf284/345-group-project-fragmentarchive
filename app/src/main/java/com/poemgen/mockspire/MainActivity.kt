package com.poemgen.mockspire

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController

class MainActivity : AppCompatActivity() {
//    private val gpt2: com.poemgen.mockspire.ml.GPT2Client by viewModels()

    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

//        val binding: ActivityMainBinding =
//            DataBindingUtil.setContentView(this, R.layout.activity_main)
//
//        binding.vm = gpt2
//
//        binding.lifecycleOwner = this

        setContentView(R.layout.activity_main_withaugs)

    }




}