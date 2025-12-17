package com.app.videomergeandroiddemo

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.app.videomergeandroiddemo.databinding.ActivityMainBinding
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.SessionState
import java.io.File
import java.io.FileOutputStream
import kotlin.io.copyTo
import kotlin.math.atan2
import androidx.core.graphics.createBitmap

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var selectedVideoUri: Uri? = null

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                selectedVideoUri = it
                playSelectedVideo(it)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupPlayer()
        clickEvent()
    }

    private fun clickEvent() {

        binding.btnUpload.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        binding.btnDownload.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Please upload video first", Toast.LENGTH_SHORT).show()
            } else {
                exportFinalVideo()
            }
        }

        binding.btnAddText.setOnClickListener {
            addTextOnVideo()
        }

        binding.btnAddSticker.setOnClickListener {
            addStickerOnVideo()
        }
    }

    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
    }

    private fun playSelectedVideo(uri: Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun addTextOnVideo() {

        val input = TextView(this)
        input.text = "Enter text here"

        val editText = android.widget.EditText(this)

        android.app.AlertDialog.Builder(this)
            .setTitle("Add Text")
            .setView(editText)
            .setPositiveButton("Add") { _, _ ->

                val textView = TextView(this)
                textView.text = editText.text.toString()
                textView.textSize = 22f
                textView.setTextColor(android.graphics.Color.WHITE)
                textView.setPadding(20, 10, 20, 10)

                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                params.gravity = Gravity.CENTER

                textView.layoutParams = params
                binding.overlayContainer.addView(textView)

                makeViewDraggable(textView)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addStickerOnVideo() {
        val imageView = ImageView(this)
        imageView.setImageResource(R.drawable.sticker)

        val params = FrameLayout.LayoutParams(200, 200)
        params.gravity = Gravity.CENTER
        imageView.layoutParams = params

        binding.overlayContainer.addView(imageView)

        makeViewDraggable(imageView)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeViewDraggable(view: View) {

        var dX = 0f
        var dY = 0f

        var oldDist = 0f
        var oldRotation = 0f

        view.setOnTouchListener { v, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    v.bringToFront()
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    oldRotation = rotation(event)
                }

                MotionEvent.ACTION_MOVE -> {

                    if (event.pointerCount == 2) {
                        // SCALE
                        val newDist = spacing(event)
                        val scale = newDist / oldDist
                        v.scaleX *= scale
                        v.scaleY *= scale
                        oldDist = newDist

                        // ROTATE
                        val newRotation = rotation(event)
                        val angle = newRotation - oldRotation
                        v.rotation += angle
                        oldRotation = newRotation

                    } else {
                        // MOVE
                        v.x = event.rawX + dX
                        v.y = event.rawY + dY
                    }
                }
            }
            true
        }
    }

    private fun rotation(event: MotionEvent): Float {
        val deltaX = event.getX(0) - event.getX(1)
        val deltaY = event.getY(0) - event.getY(1)
        return Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun saveFinalVideoToGallery(file: File) {
        try {
            val resolver = contentResolver

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }

            val uri = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return

            val outputStream = resolver.openOutputStream(uri)
            val inputStream = file.inputStream()

            inputStream.copyTo(outputStream!!)

            inputStream.close()
            outputStream.close()

            Toast.makeText(this, "✅ Video visible in Gallery!", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "❌ Gallery save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportFinalVideo() {
        if (selectedVideoUri == null) return

        Toast.makeText(this, "Downloading...", Toast.LENGTH_LONG).show()

        val inputVideoPath = copyVideoToCache(selectedVideoUri!!)

        // Read proper metadata
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, selectedVideoUri!!)
        val rotation =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt()
                ?: 0
        val rawW =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val rawH =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                ?: 0
        retriever.release()

        // Determine final display size (MOTOROLA & REDMI IMPORTANT FIX)
        val needsSwap = (rotation == 90 || rotation == 270)
        val finalW = if (needsSwap) rawH else rawW
        val finalH = if (needsSwap) rawW else rawH

        // Capture overlay in the EXACT final orientation
        val overlayImagePath = captureOverlayBitmap(finalW, finalH)

        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: run {
            Toast.makeText(this, "Storage not available", Toast.LENGTH_LONG).show()
            return
        }

        val outputFile = File(outputDir, "edited_${System.currentTimeMillis()}.mp4")
        var fixedRotation = rotation

        if (rawW > rawH) {
            if (rotation == 180) {
                fixedRotation = 0
            }
            if (rotation == 0) {
                fixedRotation = 0
            }
        }

        val rotateFilter = when (fixedRotation) {
            90 -> "transpose=1"
            180 -> "transpose=2,transpose=2"
            270 -> "transpose=2"
            else -> "null"
        }

        val command = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (BEST QUALITY, FAST)
            "-y -noautorotate -i \"$inputVideoPath\" -i \"$overlayImagePath\" " +
                    "-filter_complex \"[0:v]$rotateFilter,scale=$finalW:$finalH[v0];" +
                    "[v0][1:v]overlay=0:0:format=auto\" " +
                    "-c:v h264_mediacodec -b:v 12M -maxrate 12M -bufsize 24M " +
                    "-c:a copy -pix_fmt yuv420p " +
                    "\"${outputFile.absolutePath}\""
        } else {
            // Android 10 & below (MAX COMPATIBILITY)
            "-y -noautorotate " +
                    "-i \"$inputVideoPath\" -i \"$overlayImagePath\" " +
                    "-filter_complex \"[0:v]$rotateFilter,scale=$finalW:$finalH,format=yuv420p[v0];" +
                    "[v0][1:v]overlay=0:0\" " +
                    "-c:v mpeg4 -q:v 4 " +
                    "-c:a aac -b:a 128k " +
                    "\"${outputFile.absolutePath}\""
        }

        FFmpegKit.executeAsync(command) { session ->

            if (session.state == SessionState.COMPLETED && outputFile.exists()) {
                runOnUiThread {
                    saveFinalVideoToGallery(outputFile)
                    Toast.makeText(this, "✅ Video saved with edits!", Toast.LENGTH_LONG).show()
                }

            } else {
                runOnUiThread {
                    Toast.makeText(this, "❌ Video export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun captureOverlayBitmap(videoWidth: Int, videoHeight: Int): String {
        val folder = File(cacheDir, "overlay")
        if (!folder.exists()) folder.mkdirs()

        val file = File(folder, "overlay.png")

        val bitmap = createBitmap(videoWidth, videoHeight)

        val canvas = Canvas(bitmap)

        val scaleX = videoWidth.toFloat() / binding.overlayContainer.width
        val scaleY = videoHeight.toFloat() / binding.overlayContainer.height

        canvas.scale(scaleX, scaleY)

        binding.overlayContainer.draw(canvas)

        val fos = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        fos.flush()
        fos.close()

        return file.absolutePath
    }

    private fun copyVideoToCache(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)!!
        val file = File(cacheDir, "input.mp4")
        val outputStream = FileOutputStream(file)

        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()

        return file.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
}