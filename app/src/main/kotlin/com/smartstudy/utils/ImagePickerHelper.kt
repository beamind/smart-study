package com.smartstudy.utils

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageView
import com.smartstudy.R
import com.smartstudy.core.Logger
import java.io.File
import java.io.IOException

/**
 * 图片选择和处理工具类
 * 统一处理拍照、相册选择、手动输入和图片裁剪
 */
class ImagePickerHelper(
    private val activity: AppCompatActivity,
    private val packageName: String,
) {
    companion object {
        private const val TAG = "ImagePickerHelper"
    }

    // 图片选择器 launcher（用于从相册选择照片）
    // 使用 PickVisualMedia（Android 13+ API）
    private val imagePickerLauncher: ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri: Uri? ->
            if (uri != null) {
                onImageSelected?.invoke(uri)
            }
        }

    // 拍照 launcher
    private var takePictureLauncher: ActivityResultLauncher<Uri>? = null
    private var currentPhotoUri: Uri? = null

    // 拍照结果回调
    private var onImageSelected: ((Uri) -> Unit)? = null
    private var photoFile: File? = null

    init {
        // 初始化拍照 launcher
        takePictureLauncher =
            activity.registerForActivityResult(
                ActivityResultContracts.TakePicture(),
            ) { success ->
                val uri = currentPhotoUri
                currentPhotoUri = null
                if (success && uri != null) {
                    onImageSelected?.invoke(uri)
                } else {
                    Logger.d(TAG, "用户取消了拍照或拍照失败")
                }
            }
    }

    /**
     * 显示图片来源选择对话框
     * @param onImageSelected 图片选择回调
     */
    fun showImageSourceDialog(onImageSelected: (Uri) -> Unit) {
        this.onImageSelected = onImageSelected

        AlertDialog.Builder(activity)
            .setTitle(R.string.select_image)
            .setItems(
                arrayOf(
                    activity.getString(R.string.take_photo_option),
                    activity.getString(R.string.pick_from_gallery_option),
                    activity.getString(R.string.manual_input_option),
                ),
            ) { _, which ->
                when (which) {
                    0 -> takePhoto() // 拍照
                    1 -> pickImageFromGallery() // 从相册选择
                    2 -> showManualInputDialog() // 手动输入
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 从相册选择照片
     */
    private fun pickImageFromGallery() {
        val request =
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly,
            )
        imagePickerLauncher.launch(request)
    }

    /**
     * 拍照
     */
    private fun takePhoto() {
        try {
            photoFile = createImageFile()
        } catch (ex: IOException) {
            Logger.e(TAG, "创建图片文件失败", ex)
            return
        }

        photoFile?.let { file ->
            val photoURI =
                FileProvider.getUriForFile(
                    activity,
                    "$packageName.fileprovider",
                    file,
                )

            currentPhotoUri = photoURI
            takePictureLauncher?.launch(photoURI)
        } ?: run {
            Logger.e(TAG, "创建图片文件失败：createImageFile() 返回 null")
        }
    }

    /**
     * 创建图片文件
     */
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val imageFileName = "dictation_${System.currentTimeMillis()}"
        val storageDir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    /**
     * 处理拍照结果
     * @param requestCode 请求码
     * @param resultCode 结果码
     * @return 如果处理成功返回图片Uri，否则返回null
     */
    fun handleCameraResult(
        requestCode: Int,
        resultCode: Int,
    ): Uri? {
        if (requestCode == com.smartstudy.core.AppConfig.REQUEST_CAMERA &&
            resultCode == AppCompatActivity.RESULT_OK
        ) {
            photoFile?.let { file ->
                if (file.exists()) {
                    // 拍照成功后，将 File 转换为 Uri 并返回
                    val imageUri =
                        FileProvider.getUriForFile(
                            activity,
                            "$packageName.fileprovider",
                            file,
                        )
                    return imageUri
                }
            }
        }
        return null
    }

    /**
     * 显示手动输入对话框
     * @param onWordsInput 词语输入回调
     */
    private fun showManualInputDialog() {
        val inputEditText =
            android.widget.EditText(activity).apply {
                hint = activity.getString(R.string.manual_input_hint)
                minLines = 5
                maxLines = 10
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }

        AlertDialog.Builder(activity)
            .setTitle(R.string.manual_input_title)
            .setView(inputEditText)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val inputText = inputEditText.text.toString()
                val parsedWords = parseWordsFromInput(inputText)

                if (parsedWords.isEmpty()) {
                    ToastHelper.showShort(activity, R.string.manual_input_empty)
                    // 重新显示对话框
                    showManualInputDialog()
                    return@setPositiveButton
                }

                // 通过回调通知调用者
                onManualInput?.invoke(parsedWords)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var onManualInput: ((List<String>) -> Unit)? = null

    /**
     * 显示手动输入对话框（带回调）
     * @param onWordsInput 词语输入回调
     */
    fun showManualInputDialog(onWordsInput: (List<String>) -> Unit) {
        this.onManualInput = onWordsInput
        showManualInputDialog()
    }

    /**
     * 从输入文本中解析词语
     * 按照回车（换行符）拆分
     */
    private fun parseWordsFromInput(input: String): List<String> {
        return input.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 配置裁剪视图
     * @param cropImageView 裁剪视图
     * @param onImageLoaded 图片加载完成回调
     */
    fun setupCropImageView(
        cropImageView: CropImageView,
        onImageLoaded: (Boolean) -> Unit,
    ) {
        cropImageView.apply {
            guidelines = CropImageView.Guidelines.ON
            // CanHub CropImageView 的 setAspectRatio(x, y) 要求 x/y > 0。
            // 传入 0 会导致裁剪框进入异常状态（例如无法拖动/调整边界）。
            // 自由比例只需要关闭固定比例即可。
            setFixedAspectRatio(false)
            // CropImageView 的 setImageUriAsync 会自动处理 EXIF orientation
            // 确保图片方向正确显示

            // 设置图片加载完成回调
            setOnSetImageUriCompleteListener { _, uri, error ->
                if (error == null) {
                    Logger.d(TAG, "图片加载成功: $uri")
                    onImageLoaded(true)
                } else {
                    Logger.e(TAG, "图片加载失败: $uri", error)
                    onImageLoaded(false)
                }
            }
        }
    }

    /**
     * 获取裁剪后的图片
     * @param cropImageView 裁剪视图
     * @return 裁剪后的Bitmap，如果失败返回null
     */
    fun getCroppedImage(cropImageView: CropImageView): Bitmap? {
        return try {
            cropImageView.getCroppedImage()
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，裁剪图片失败", e)
            null
        } catch (e: OutOfMemoryError) {
            Logger.e(TAG, "内存不足，裁剪图片失败", e)
            null
        }
    }
}
