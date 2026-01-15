package com.flashlight.torch

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.flashlight.torch.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    
    private var isFlashlightOn = false
    private var currentBrightnessLevel = 5
    private var maxBrightnessLevel = 10
    private var supportsBrightnessControl = false
    private var isChangingBrightness = false  // Prevent rapid changes
    
    // Strobe mode
    private var isStrobeMode = false
    private var strobeSpeed = 5  // Hz (flashes per second)
    private var strobeState = false  // Current on/off state during strobe
    private val strobeHandler = Handler(Looper.getMainLooper())
    private val strobeRunnable = object : Runnable {
        override fun run() {
            if (isStrobeMode && isFlashlightOn) {
                toggleTorchForStrobe(strobeState)
                val delay = (1000L / strobeSpeed) / 2  // Half period for on/off
                strobeHandler.postDelayed(this, delay)
                strobeState = !strobeState  // Toggle AFTER setting torch
            }
        }
    }
    
    private val levelIndicators = mutableListOf<View>()
    
    // Torch callback to track actual torch state
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(camId: String, enabled: Boolean) {
            if (camId == cameraId) {
                runOnUiThread {
                    // Don't update state during strobe mode - strobe controls the torch directly
                    if (!isStrobeMode && enabled != isFlashlightOn) {
                        isFlashlightOn = enabled
                        if (enabled) {
                            updateUIForOnState()
                        } else {
                            updateUIForOffState()
                        }
                    }
                }
            }
        }
        
        override fun onTorchStrengthLevelChanged(camId: String, newStrengthLevel: Int) {
            if (camId == cameraId) {
                runOnUiThread {
                    isChangingBrightness = false
                }
            }
        }
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeFlashlight()
        } else {
            showToast(getString(R.string.permission_required))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        checkPermissionAndInitialize()
    }

    override fun onStop() {
        super.onStop()
        // Stop strobe and turn off flashlight when app goes to background
        stopStrobe()
        if (isFlashlightOn) {
            turnOffFlashlight()
        }
    }

    private fun checkPermissionAndInitialize() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeFlashlight()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) -> {
                // Show explanation then request
                showToast(getString(R.string.permission_required))
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeFlashlight() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val manager = cameraManager ?: return
        
        try {
            // Find camera with flash
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (hasFlash) {
                    cameraId = id
                    
                    // Check if brightness control is supported (Android 13+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val maxLevel = characteristics.get(
                            CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
                        ) ?: 1
                        
                        if (maxLevel > 1) {
                            supportsBrightnessControl = true
                            maxBrightnessLevel = maxLevel
                            // Scale slider to actual hardware levels
                            binding.brightnessSlider.valueFrom = 1f
                            binding.brightnessSlider.valueTo = maxLevel.toFloat()
                            binding.brightnessSlider.stepSize = 1f
                            currentBrightnessLevel = (maxLevel / 2).coerceAtLeast(1)
                            binding.brightnessSlider.value = currentBrightnessLevel.toFloat()
                        } else {
                            // Device reports max level of 1, no variable brightness
                            supportsBrightnessControl = false
                        }
                    }
                    break
                }
            }
            
            if (cameraId == null) {
                showToast(getString(R.string.no_flash_available))
                binding.powerButton.isEnabled = false
            } else {
                // Register torch callback to track state
                manager.registerTorchCallback(torchCallback, null)
            }
            
            setupLevelIndicators()
            updateLevelLabel()
            
        } catch (e: Exception) {
            showToast(getString(R.string.no_flash_available))
            binding.powerButton.isEnabled = false
        }
    }

    private fun setupUI() {
        // Power button click listener
        binding.powerButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleFlashlight()
        }
        
        // Brightness slider listener - update UI only during drag
        binding.brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentBrightnessLevel = value.toInt().coerceIn(1, maxBrightnessLevel)
                updateLevelLabel()
                updateLevelIndicators()
            }
        }
        
        // Apply brightness only when user finishes dragging to avoid rapid API calls
        binding.brightnessSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                provideHapticFeedback()
            }
            
            override fun onStopTrackingTouch(slider: Slider) {
                provideHapticFeedback()
                // Apply brightness when user stops dragging
                if (isFlashlightOn && supportsBrightnessControl && !isStrobeMode) {
                    applyBrightness()
                }
            }
        })
        
        // Strobe mode switch
        binding.strobeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isStrobeMode = isChecked
            provideHapticFeedback()
            
            // Show/hide speed controls with animation
            binding.speedControlContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            if (isChecked && isFlashlightOn) {
                // Start strobe
                startStrobe()
            } else {
                // Stop strobe and restore steady light if on
                stopStrobe()
                if (isFlashlightOn) {
                    turnOnTorchSteady()
                }
            }
        }
        
        // Speed slider listener
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                strobeSpeed = value.toInt()
                updateSpeedLabel()
                
                // Restart strobe with new speed if active
                if (isStrobeMode && isFlashlightOn) {
                    stopStrobe()
                    startStrobe()
                }
            }
        }
        
        updateSpeedLabel()
    }
    
    private fun updateSpeedLabel() {
        binding.speedValueLabel.text = "$strobeSpeed ${getString(R.string.hz_suffix)}"
    }

    private fun setupLevelIndicators() {
        binding.levelIndicatorsContainer.removeAllViews()
        levelIndicators.clear()
        
        val indicatorCount = minOf(maxBrightnessLevel, 10) // Show max 10 indicators
        
        for (i in 1..indicatorCount) {
            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.level_indicator_size) * 2,
                    resources.getDimensionPixelSize(R.dimen.level_indicator_size) / 2
                ).apply {
                    marginEnd = if (i < indicatorCount) 
                        resources.getDimensionPixelSize(R.dimen.spacing_small) else 0
                }
                setBackgroundResource(R.drawable.bg_level_indicator)
            }
            levelIndicators.add(indicator)
            binding.levelIndicatorsContainer.addView(indicator)
        }
        
        updateLevelIndicators()
    }

    private fun updateLevelIndicators() {
        val indicatorCount = levelIndicators.size
        val activeCount = if (maxBrightnessLevel > 10) {
            (currentBrightnessLevel.toFloat() / maxBrightnessLevel * indicatorCount).toInt()
        } else {
            currentBrightnessLevel
        }
        
        levelIndicators.forEachIndexed { index, view ->
            val isActive = index < activeCount
            view.setBackgroundResource(
                if (isActive) R.drawable.bg_level_indicator_active 
                else R.drawable.bg_level_indicator
            )
            
            // Animate the indicator
            if (isActive && isFlashlightOn) {
                view.animate()
                    .scaleY(1.3f)
                    .setDuration(100)
                    .withEndAction {
                        view.animate().scaleY(1f).setDuration(100).start()
                    }
                    .start()
            }
        }
    }

    private fun updateLevelLabel() {
        val displayLevel = if (maxBrightnessLevel > 10) {
            ((currentBrightnessLevel.toFloat() / maxBrightnessLevel) * 10).toInt().coerceIn(1, 10)
        } else {
            currentBrightnessLevel
        }
        binding.levelLabel.text = getString(R.string.level_prefix) + " $displayLevel"
    }

    private fun toggleFlashlight() {
        if (isFlashlightOn) {
            turnOffFlashlight()
        } else {
            turnOnFlashlight()
        }
    }

    private fun turnOnFlashlight() {
        if (!hasCameraPermission()) {
            showToast(getString(R.string.permission_required))
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        
        val manager = cameraManager ?: return
        cameraId?.let { id ->
            try {
                if (isStrobeMode) {
                    // Start with strobe mode
                    isFlashlightOn = true
                    updateUIForOnState()
                    startStrobe()
                } else {
                    // Normal steady light
                    if (supportsBrightnessControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val safeLevel = currentBrightnessLevel.coerceIn(1, maxBrightnessLevel)
                        manager.turnOnTorchWithStrengthLevel(id, safeLevel)
                    } else {
                        manager.setTorchMode(id, true)
                    }
                    isFlashlightOn = true
                    updateUIForOnState()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Failed to turn on flashlight: ${e.message}")
                return@let
            }
            provideHapticFeedback()
        }
    }
    
    private fun turnOnTorchSteady() {
        val manager = cameraManager ?: return
        cameraId?.let { id ->
            try {
                if (supportsBrightnessControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val safeLevel = currentBrightnessLevel.coerceIn(1, maxBrightnessLevel)
                    manager.turnOnTorchWithStrengthLevel(id, safeLevel)
                } else {
                    manager.setTorchMode(id, true)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    private fun toggleTorchForStrobe(on: Boolean) {
        val manager = cameraManager ?: return
        cameraId?.let { id ->
            try {
                manager.setTorchMode(id, on)
            } catch (e: Exception) {
                // Ignore strobe errors
            }
        }
    }
    
    private fun startStrobe() {
        strobeState = true  // Start with light ON
        strobeHandler.removeCallbacks(strobeRunnable)
        toggleTorchForStrobe(true)  // Turn on immediately
        val delay = (1000L / strobeSpeed) / 2
        strobeHandler.postDelayed(strobeRunnable, delay)  // Schedule first toggle
        strobeState = false  // Next state will be OFF
    }
    
    private fun stopStrobe() {
        strobeHandler.removeCallbacks(strobeRunnable)
        strobeState = false
    }

    private fun turnOffFlashlight() {
        // Stop strobe if active
        stopStrobe()
        
        val manager = cameraManager ?: return
        cameraId?.let { id ->
            try {
                manager.setTorchMode(id, false)
                isFlashlightOn = false
                updateUIForOffState()
            } catch (e: Exception) {
                showToast("Failed to turn off flashlight")
                return@let
            }
            provideHapticFeedback()
        }
    }

    private fun applyBrightness() {
        if (!isFlashlightOn || !supportsBrightnessControl || isChangingBrightness) return
        if (!hasCameraPermission()) return
        
        val manager = cameraManager ?: return
        cameraId?.let { id ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isChangingBrightness = true
                    val safeLevel = currentBrightnessLevel.coerceIn(1, maxBrightnessLevel)
                    manager.turnOnTorchWithStrengthLevel(id, safeLevel)
                }
            } catch (e: Exception) {
                isChangingBrightness = false
                e.printStackTrace()
                showToast("Brightness change failed: ${e.message}")
            }
        }
    }

    private fun updateUIForOnState() {
        // Status badge
        binding.statusBadge.text = getString(R.string.flashlight_on)
        binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary))
        
        // Power button animation
        animatePowerButton(true)
        
        // Update level indicators
        updateLevelIndicators()
    }

    private fun updateUIForOffState() {
        // Status badge
        binding.statusBadge.text = getString(R.string.flashlight_off)
        binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        
        // Power button animation
        animatePowerButton(false)
    }

    private fun animatePowerButton(isOn: Boolean) {
        val powerButton = binding.powerButton
        val powerGlow = binding.powerGlow
        
        if (isOn) {
            // Scale up animation
            val scaleX = ObjectAnimator.ofFloat(powerButton, View.SCALE_X, 1f, 0.9f, 1.05f, 1f)
            val scaleY = ObjectAnimator.ofFloat(powerButton, View.SCALE_Y, 1f, 0.9f, 1.05f, 1f)
            
            // Glow fade in
            val glowAlpha = ObjectAnimator.ofFloat(powerGlow, View.ALPHA, 0f, 1f)
            
            AnimatorSet().apply {
                playTogether(scaleX, scaleY, glowAlpha)
                duration = 300
                interpolator = OvershootInterpolator()
                start()
            }
            
            // Update button appearance
            powerButton.setBackgroundResource(R.drawable.bg_power_button_on)
            powerButton.imageTintList = ContextCompat.getColorStateList(this, R.color.primary)
            
            // Pulse animation for glow
            startGlowPulseAnimation()
            
        } else {
            // Scale animation
            val scaleX = ObjectAnimator.ofFloat(powerButton, View.SCALE_X, 1f, 0.95f, 1f)
            val scaleY = ObjectAnimator.ofFloat(powerButton, View.SCALE_Y, 1f, 0.95f, 1f)
            
            // Glow fade out
            val glowAlpha = ObjectAnimator.ofFloat(powerGlow, View.ALPHA, 1f, 0f)
            
            AnimatorSet().apply {
                playTogether(scaleX, scaleY, glowAlpha)
                duration = 200
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            
            // Update button appearance
            powerButton.setBackgroundResource(R.drawable.bg_power_button_off)
            powerButton.imageTintList = ContextCompat.getColorStateList(this, R.color.text_dim)
            
            // Stop pulse animation
            stopGlowPulseAnimation()
        }
    }

    private var glowAnimator: ValueAnimator? = null

    private fun startGlowPulseAnimation() {
        stopGlowPulseAnimation()
        
        glowAnimator = ValueAnimator.ofFloat(0.7f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                binding.powerGlow.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun stopGlowPulseAnimation() {
        glowAnimator?.cancel()
        glowAnimator = null
    }

    private fun provideHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30)
                }
            }
        } catch (e: Exception) {
            // Haptic feedback not available, ignore silently
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGlowPulseAnimation()
        stopStrobe()
        
        cameraManager?.let { manager ->
            // Unregister torch callback
            try {
                manager.unregisterTorchCallback(torchCallback)
            } catch (e: Exception) {
                // Ignore
            }
            
            // Ensure flashlight is off when app is destroyed
            if (isFlashlightOn) {
                try {
                    cameraId?.let { manager.setTorchMode(it, false) }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}

