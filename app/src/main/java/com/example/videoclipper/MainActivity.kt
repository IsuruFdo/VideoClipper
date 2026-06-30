package com.example.videoclipper

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.videoclipper.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MAX_CLIP_MS = 60_000L
    }

    private lateinit var binding: ActivityMainBinding

    private var player: ExoPlayer? = null
    private var sourceUri: Uri? = null

    private var clipStartMs: Long = 0L
    private var clipEndMs: Long = 0L

    private var transformer: Transformer? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var playbackTickRunnable: Runnable? = null

    // Opens the system file picker scoped to video files.
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { openVideo(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        binding.btnOpen.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
        }

        binding.btnSetStart.setOnClickListener {
            player?.let {
                clipStartMs = it.currentPosition
                updateClipLabel()
            }
        }

        binding.btnSetEnd.setOnClickListener {
            player?.let {
                clipEndMs = it.currentPosition
                updateClipLabel()
            }
        }

        binding.etStart.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyTypedStart()
                true
            } else {
                false
            }
        }

        binding.etEnd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyTypedEnd()
                true
            } else {
                false
            }
        }

        binding.btnTrim.setOnClickListener { trimAndSave() }
        binding.btnTrim.isEnabled = false

        binding.btnMenu.setOnClickListener {
            binding.root.openDrawer(GravityCompat.START)
        }

        binding.menuMyClips.setOnClickListener {
            binding.root.closeDrawer(GravityCompat.START)
            try {
                showClipsDialog()
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open My Clips.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.menuSettings.setOnClickListener {
            binding.root.closeDrawer(GravityCompat.START)
            showSettingsDialog()
        }

        binding.menuAbout.setOnClickListener {
            binding.root.closeDrawer(GravityCompat.START)
            showAboutDialog()
        }

        val year = Calendar.getInstance().get(Calendar.YEAR)
        binding.tvCopyright.text = "© $year VideoClipper"

        onBackPressedDispatcher.addCallback(this) {
            if (binding.root.isDrawerOpen(GravityCompat.START)) {
                binding.root.closeDrawer(GravityCompat.START)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    // Pads the layout by the system bars (status bar / notch) instead of
    // letting the header draw underneath the clock / status icons.
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        transformer?.cancel()
    }

    // ---------- Playback ----------

    private fun initializePlayer() {
        if (player != null) return
        val exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        player = exoPlayer
        sourceUri?.let { loadMediaItem(it) }
        startPlaybackTicker()
    }

    private fun releasePlayer() {
        stopPlaybackTicker()
        player?.release()
        player = null
    }

    private fun openVideo(uri: Uri) {
        sourceUri = uri
        clipStartMs = 0L
        clipEndMs = 0L
        updateClipLabel()
        loadMediaItem(uri)
        binding.emptyState.visibility = View.GONE
    }

    private fun loadMediaItem(uri: Uri) {
        val exoPlayer = player ?: return
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Live "00:05 / 01:32" readout under the player, refreshed a few times a second.
    private fun startPlaybackTicker() {
        val runnable = object : Runnable {
            override fun run() {
                val exoPlayer = player
                if (exoPlayer != null) {
                    val duration = exoPlayer.duration.coerceAtLeast(0L)
                    binding.tvPlaybackTime.text =
                        "${formatMs(exoPlayer.currentPosition)} / ${formatMs(duration)}"
                }
                progressHandler.postDelayed(this, 250)
            }
        }
        playbackTickRunnable = runnable
        progressHandler.post(runnable)
    }

    private fun stopPlaybackTicker() {
        playbackTickRunnable?.let { progressHandler.removeCallbacks(it) }
        playbackTickRunnable = null
    }

    // ---------- Clip marking ----------

    private fun updateClipLabel() {
        binding.etStart.setText(formatMs(clipStartMs))
        binding.etEnd.setText(formatMs(clipEndMs))

        val durationMs = (clipEndMs - clipStartMs).coerceAtLeast(0L)
        binding.tvDuration.text = "Clip length: ${durationMs / 1000}s"

        val tooLong = durationMs > MAX_CLIP_MS
        binding.tvRangeWarning.visibility = if (tooLong) View.VISIBLE else View.GONE

        binding.btnTrim.isEnabled = sourceUri != null && clipEndMs > clipStartMs && !tooLong
    }

    // Plain arithmetic — NOT SimpleDateFormat. Formatting an elapsed duration
    // with SimpleDateFormat silently applies the device's timezone offset.
    // A duration isn't a point in time, so it should never touch a timezone.
    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun parseMmSs(text: String): Long? {
        val match = Regex("^(\\d{1,2}):([0-5]\\d)$").matchEntire(text.trim()) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        return (minutes * 60 + seconds) * 1000
    }

    private fun applyTypedStart() {
        val ms = parseMmSs(binding.etStart.text.toString())
        if (ms == null) {
            Toast.makeText(this, "Enter the start time as mm:ss", Toast.LENGTH_SHORT).show()
            binding.etStart.setText(formatMs(clipStartMs))
        } else {
            clipStartMs = ms
            player?.seekTo(ms)
            updateClipLabel()
        }
        hideKeyboard()
    }

    private fun applyTypedEnd() {
        val ms = parseMmSs(binding.etEnd.text.toString())
        if (ms == null) {
            Toast.makeText(this, "Enter the end time as mm:ss", Toast.LENGTH_SHORT).show()
            binding.etEnd.setText(formatMs(clipEndMs))
        } else {
            clipEndMs = ms
            player?.seekTo(ms)
            updateClipLabel()
        }
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ---------- Trim / export ----------

    private fun trimAndSave() {
        val uri = sourceUri
        if (uri == null) {
            Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show()
            return
        }
        if (clipEndMs <= clipStartMs) {
            Toast.makeText(this, "Set a valid start and end point", Toast.LENGTH_SHORT).show()
            return
        }
        if (clipEndMs - clipStartMs > MAX_CLIP_MS) {
            showMessageDialog(
                title = "Clip too long",
                message = "Clips can be at most 1 minute. Shorten the range and try again."
            )
            return
        }

        player?.pause()

        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val outputFile = File(outputDir, "clip_${System.currentTimeMillis()}.mp4")

        val clippedItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipStartMs)
                    .setEndPositionMs(clipEndMs)
                    .build()
            )
            .build()

        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvProgressLabel.visibility = View.VISIBLE
        binding.tvProgressLabel.text = "Exporting… 0%"
        binding.btnTrim.isEnabled = false

        val newTransformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onExportFinished(success = true, path = outputFile.absolutePath, error = null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    onExportFinished(success = false, path = null, error = exportException.message)
                }
            })
            .build()

        transformer = newTransformer
        newTransformer.start(clippedItem, outputFile.absolutePath)
        startProgressPolling()
    }

    private fun startProgressPolling() {
        val progressHolder = ProgressHolder()
        val runnable = object : Runnable {
            override fun run() {
                val currentTransformer = transformer ?: return
                currentTransformer.getProgress(progressHolder)
                binding.progressBar.progress = progressHolder.progress
                binding.tvProgressLabel.text = "Exporting… ${progressHolder.progress}%"
                progressHandler.postDelayed(this, 300)
            }
        }
        progressRunnable = runnable
        progressHandler.post(runnable)
    }

    private fun onExportFinished(success: Boolean, path: String?, error: String?) {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        transformer = null
        binding.progressBar.visibility = View.GONE
        binding.tvProgressLabel.visibility = View.GONE
        updateClipLabel()

        if (success) {
            showMessageDialog(
                title = "Clip saved 🎉",
                message = "Your trimmed clip was saved to:\n\n$path"
            )
        } else {
            showMessageDialog(
                title = "Export failed",
                message = error ?: "Something went wrong while exporting the clip."
            )
        }
    }

    private fun showMessageDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Drawer: My Clips ----------

    private fun showClipsDialog() {
        val clipsDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val clips = clipsDir?.listFiles { f -> f.isFile && f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("My Clips (${clips.size})")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Close", null)
            .create()

        if (clips.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No clips saved yet. Trim a video to see it here."
                setTextColor(getThemeColor(android.R.attr.textColorSecondary))
            })
        } else {
            val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
            for (clip in clips) {
                // Each row is built independently — if one file is corrupt
                // and thumbnail extraction throws, only that row is skipped
                // instead of the whole dialog crashing.
                try {
                    container.addView(buildClipRow(clip, sdf, dialog))
                } catch (e: Exception) {
                    container.addView(TextView(this).apply {
                        text = "⚠️  ${clip.name} (couldn't load preview)"
                        setTextColor(getThemeColor(android.R.attr.textColorSecondary))
                        setPadding(0, dpToPx(8), 0, dpToPx(8))
                    })
                }
            }
        }

        dialog.show()
    }

    // One row: a thumbnail frame, the clip's name + saved date, an Open
    // button, and a delete (🗑) button. `dialog` is the currently showing
    // My Clips dialog — deleting a clip dismisses it and reopens a fresh
    // copy so the list and the "(N)" count in the title both stay accurate.
    private fun buildClipRow(clip: File, sdf: SimpleDateFormat, dialog: AlertDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dpToPx(10)) }
        }

        val thumbSize = dpToPx(56)
        val thumbnail = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(getThemeColor(android.R.attr.colorBackground))
        }
        loadVideoThumbnail(clip.absolutePath, thumbnail)
        row.addView(thumbnail)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dpToPx(12) }
        }
        textColumn.addView(TextView(this).apply {
            text = clip.name
            setTextColor(getThemeColor(android.R.attr.textColorPrimary))
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        textColumn.addView(TextView(this).apply {
            text = sdf.format(Date(clip.lastModified()))
            setTextColor(getThemeColor(android.R.attr.textColorSecondary))
            textSize = 12f
        })
        row.addView(textColumn)

        val openButton = TextView(this).apply {
            text = "Open"
            textSize = 13f
            setTextColor(getThemeColor(android.R.attr.colorAccent))
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            isClickable = true
            isFocusable = true
            background = getThemeDrawable(android.R.attr.selectableItemBackgroundBorderless)
            setOnClickListener { openClipLocation(clip) }
        }
        row.addView(openButton)

        val deleteButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_delete)
            imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#F472B6")
            )
            val size = dpToPx(36)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dpToPx(4)
            }
            val pad = dpToPx(6)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            background = getThemeDrawable(android.R.attr.selectableItemBackgroundBorderless)
            contentDescription = "Delete clip"
            setOnClickListener { confirmDeleteClip(clip, dialog) }
        }
        row.addView(deleteButton)

        return row
    }

    // Asks for confirmation, deletes the file, then refreshes the dialog so
    // the row disappears and the "(N)" count updates immediately.
    private fun confirmDeleteClip(clip: File, dialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle("Delete this clip?")
            .setMessage("\"${clip.name}\" will be permanently deleted. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = try {
                    clip.delete()
                } catch (e: Exception) {
                    false
                }
                if (deleted) {
                    Toast.makeText(this, "Clip deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Couldn't delete that clip", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                showClipsDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Extracts a frame from the video file as the thumbnail. Every step is
    // allowed to fail silently (corrupt file, unsupported codec, low memory)
    // — in that case the ImageView is simply left blank instead of crashing.
    private fun loadVideoThumbnail(path: String, target: ImageView) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val frame = retriever.getFrameAtTime(0)
            if (frame != null) {
                target.setImageBitmap(frame)
            }
        } catch (e: Exception) {
            // Leave the thumbnail blank — the row's text still renders fine.
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore — nothing more we can do if release itself fails.
            }
        }
    }

    // Opens this specific clip's containing folder (or the clip itself, as
    // a fallback) in whatever app the user has installed. Every failure
    // path shows a toast instead of letting the exception propagate and
    // crash the app — some devices throw earlier than startActivity() for
    // intents no installed app can resolve, which is why both the intent
    // *resolution* and the launch itself are guarded here.
    private fun openClipLocation(clip: File) {
        val authority = "$packageName.fileprovider"
        try {
            val uri: Uri = FileProvider.getUriForFile(this, authority, clip)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                openClipsFolder()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Couldn't open that clip. It's saved at:\n${clip.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Hands the app's whole clips folder to whatever file manager the user
    // has, via FileProvider since the folder lives in app-private external
    // storage and can't be exposed as a raw file:// path. Some devices throw
    // during intent resolution rather than at startActivity() for MIME types
    // no app declares support for, so resolveActivity() is checked first.
    private fun openClipsFolder() {
        val clipsDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", clipsDir
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(
                    this,
                    "No file manager app found. Clips are saved at:\n${clipsDir.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Clips are saved at:\n${clipsDir.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- Drawer: Settings ----------

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(VideoClipperApp.PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getString(VideoClipperApp.KEY_THEME_MODE, "system") ?: "system"
        val options = arrayOf("Light", "Dark", "Follow system")
        val values = arrayOf("light", "dark", "system")
        val checkedIndex = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Appearance")
            .setSingleChoiceItems(options, checkedIndex) { dialog, index ->
                val chosen = values[index]
                prefs.edit().putString(VideoClipperApp.KEY_THEME_MODE, chosen).apply()
                applyThemeMode(chosen)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Drawer: About ----------

    private fun showAboutDialog() {
        val message = """
            VideoClipper lets you open any video on your device, scrub through it, mark a start and end point, and export that range as its own short clip — up to one minute long.

            Features:
            • Open and play local video files
            • Live playback time readout
            • Mark clip points by playhead position or by typing mm:ss
            • Trim & export without leaving the app
            • Browse previously saved clips
            • Light, dark, or system-matched appearance
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("About VideoClipper")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun getThemeDrawable(attr: Int): android.graphics.drawable.Drawable? {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return try {
            androidx.core.content.ContextCompat.getDrawable(this, typedValue.resourceId)
        } catch (e: Exception) {
            null
        }
    }
}