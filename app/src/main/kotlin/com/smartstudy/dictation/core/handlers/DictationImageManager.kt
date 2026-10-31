package com.smartstudy.dictation.core.handlers

import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.View
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageView
import com.smartstudy.R
import com.smartstudy.core.AppConfig
import com.smartstudy.core.AppError
import com.smartstudy.core.AppErrorKind
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.ImageProcessingHelper
import com.smartstudy.dictation.core.ImageUnderstandingService
import com.smartstudy.dictation.ui.DictationViewModel
import com.smartstudy.utils.NetworkUtils
import com.smartstudy.utils.StorageUtils
import com.smartstudy.utils.ToastHelper
import java.io.File
import java.io.IOException

/**
 * 听写图片处理管理器
 *
 * 负责图片选择、裁剪、识别等逻辑，将图片处理相关功能从Activity中分离出来，
 * 提高代码的可维护性和可测试性。
 *
 * 功能特性：
 * - 支持拍照、相册选择、手动输入三种方式
 * - 自动图片优化和内存管理
 * - 统一的错误处理
 * - 所有异步操作通过ViewModel执行，避免Activity持有协程引用
 *
 * 使用示例：
 * ```
 * val imageManager = DictationImageManager(
 *     activity = this,
 *     viewModel = viewModel,
 *     onImageRecognized = { words -> handleWords(words) },
 *     onImageRecognitionError = { error -> handleError(error) }
 * )
 * imageManager.initCropViews()
 * imageManager.showImageSourceDialog()
 * ```
 *
 * @param activity Activity实例，用于访问UI
 * @param viewModel ViewModel实例，用于执行异步操作
 * @param onImageRecognized 图片识别成功回调，参数为识别到的词语列表
 * @param onImageRecognitionError 图片识别失败回调，参数为错误信息
 */
class DictationImageManager(
    private val activity: AppCompatActivity,
    private val viewModel: DictationViewModel,
    private val onImageRecognized: (List<String>) -> Unit,
    private val onImageRecognitionError: (AppError) -> Unit,
) {
    companion object {
        private const val TAG = "DictationImageManager"
        private const val MANUAL_INPUT_MIN_LINES = 5
        private const val MANUAL_INPUT_MAX_LINES = 10
    }

    // 视图组件
    var ivPhoto: ImageView? = null
    var btnTakePhoto: androidx.cardview.widget.CardView? = null
    var cropImageView: CropImageView? = null
    var cropContainer: View? = null
    var btnCropDone: android.widget.Button? = null
    var btnCropCancel: android.widget.Button? = null
    var cropButtonLayout: View? = null
    var cardPhoto: androidx.cardview.widget.CardView? = null
    var placeholderLayout: View? = null
    var btnViewErrors: androidx.cardview.widget.CardView? = null
    var btnErrorDictation: androidx.cardview.widget.CardView? = null
    var cardProgress: androidx.cardview.widget.CardView? = null
    var cardAnswers: androidx.cardview.widget.CardView? = null

    // 状态
    private var isImageLoaded = false
    private var currentPhotoUri: Uri? = null
    private var currentPhotoFile: File? = null // 跟踪当前照片文件，用于后续清理
    private var dictationType: String = AppConfig.TYPE_CHINESE

    // 图片选择器
    val imagePickerLauncher: ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> =
        activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            uri?.let { startCropImage(it) }
        }

    // 拍照 launcher
    private val takePictureLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val uri = currentPhotoUri
            currentPhotoUri = null
            if (success && uri != null) {
                startCropImage(uri)
            } else {
                Logger.d(TAG, "用户取消了拍照或拍照失败")
            }
        }

    /**
     * 设置听写类型
     */
    fun setDictationType(type: String) {
        dictationType = type
    }

    /**
     * 初始化裁剪视图
     */
    fun initCropViews() {
        cropImageView?.apply {
            guidelines = CropImageView.Guidelines.ON
            setFixedAspectRatio(false)
            // CropImageView 的 setImageUriAsync 会自动处理 EXIF orientation
            // 确保图片方向正确显示

            setOnSetImageUriCompleteListener { _, uri, error ->
                if (error == null) {
                    Logger.d(TAG, "图片加载成功: $uri")
                    isImageLoaded = true
                } else {
                    Logger.e(TAG, "图片加载失败: $uri", error)
                    isImageLoaded = false
                    activity.runOnUiThread {
                        ToastHelper.showLong(activity, R.string.image_load_failed, error.message ?: "")
                        cancelCrop()
                    }
                }
            }
        }

        btnCropDone?.setOnClickListener {
            performCrop()
        }

        btnCropCancel?.setOnClickListener {
            cancelCrop()
        }
    }

    /**
     * 显示图片来源选择对话框
     */
    fun showImageSourceDialog() {
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
                    0 -> takePhoto()
                    1 -> pickImageFromGallery()
                    2 -> showManualInputDialog(onImageRecognized)
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
            val photoFile = createImageFile()
            val photoUri =
                FileProvider.getUriForFile(
                    activity,
                    "${activity.applicationContext.packageName}.fileprovider",
                    photoFile,
                )
            currentPhotoUri = photoUri
            currentPhotoFile = photoFile // 保存文件引用，用于后续清理
            takePictureLauncher.launch(photoUri)
        } catch (e: SecurityException) {
            handlePhotoCreationError(e, R.string.error_permission_denied)
        } catch (e: IOException) {
            handlePhotoCreationError(e, R.string.error_file_creation_failed)
        } catch (e: IllegalStateException) {
            handlePhotoCreationError(e, R.string.error_state)
        }
    }

    /**
     * 处理拍照创建错误
     */
    private fun handlePhotoCreationError(error: Exception, errorStringRes: Int) {
        Logger.e(TAG, "创建图片文件失败: ${error.javaClass.simpleName}", error)
        clearPhotoReferences()
        val errorMessage = error.message ?: activity.getString(errorStringRes)
        ToastHelper.showShort(activity, R.string.photo_failed, errorMessage)
    }

    /**
     * 清理照片引用
     */
    private fun clearPhotoReferences() {
        currentPhotoUri = null
        currentPhotoFile = null
    }

    /**
     * 创建图片文件
     */
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val imageFileName = "dictation_${System.currentTimeMillis()}"
        val storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    /**
     * 开始裁剪图片
     * @param imageUri 图片URI，可能来自拍照（FileProvider URI）或相册选择（Content URI）
     */
    fun startCropImage(imageUri: Uri) {
        try {
            Logger.d(TAG, "开始裁剪图片: $imageUri")
            // 注意：如果是从相册选择的，currentPhotoFile 为 null，不会删除用户相册中的文件

            showCropUI()
            adjustCropConstraints()
            hideAnswersCard()

            isImageLoaded = false

            cropContainer?.post {
                loadImageForCrop(imageUri)
            }
        } catch (e: SecurityException) {
            handleStartCropError(e, R.string.error_permission_denied)
        } catch (e: IllegalStateException) {
            handleStartCropError(e, R.string.error_state)
        }
    }

    /**
     * 处理开始裁剪错误
     */
    private fun handleStartCropError(error: Exception, defaultErrorRes: Int) {
        Logger.e(TAG, "开始裁剪图片失败: ${error.javaClass.simpleName}", error)
        val errorMessage = error.message ?: activity.getString(defaultErrorRes)
        ToastHelper.showShort(activity, R.string.image_start_crop_failed, errorMessage)
    }

    /**
     * 显示裁剪UI
     */
    private fun showCropUI() {
        cardPhoto?.visibility = View.VISIBLE
        ivPhoto?.visibility = View.GONE
        placeholderLayout?.visibility = View.GONE
        cropContainer?.visibility = View.VISIBLE
        btnTakePhoto?.visibility = View.GONE
        btnViewErrors?.visibility = View.GONE
        btnErrorDictation?.visibility = View.GONE
        cropButtonLayout?.visibility = View.VISIBLE
    }

    /**
     * 调整裁剪约束
     */
    private fun adjustCropConstraints() {
        val layoutParams = cardPhoto?.layoutParams as? ConstraintLayout.LayoutParams
        layoutParams?.let {
            it.bottomToTop = -1
            it.bottomToBottom = 0
            cardProgress?.let { progressCard ->
                it.topToBottom = progressCard.id
            } ?: run {
                it.topToTop = -1
            }
            it.topToTop = -1
            it.bottomMargin = activity.resources.getDimensionPixelSize(com.smartstudy.R.dimen.spacing_xl)
            cardPhoto?.layoutParams = it
        }
    }

    /**
     * 隐藏答案卡片
     */
    private fun hideAnswersCard() {
        cardAnswers?.visibility = View.GONE
    }

    /**
     * 加载图片用于裁剪
     */
    private fun loadImageForCrop(imageUri: Uri) {
        try {
            cropImageView?.setImageBitmap(null)

            // 先加载 Bitmap 并处理 EXIF orientation，保持拍照时的原始方向
            val context = activity.applicationContext
            val bitmap = ImageProcessingHelper.loadBitmapWithOrientation(imageUri, context)

            if (bitmap != null) {
                // 使用处理后的 Bitmap 设置到 CropImageView
                cropImageView?.setImageBitmap(bitmap)
                isImageLoaded = true
                Logger.d(TAG, "图片加载成功，已处理 EXIF orientation")
            } else {
                throw IOException("无法加载图片")
            }
        } catch (e: IllegalArgumentException) {
            handleImageLoadError(e, R.string.image_uri_invalid)
        } catch (e: IOException) {
            handleImageLoadError(e, R.string.image_file_read_failed)
        } catch (e: OutOfMemoryError) {
            handleImageLoadError(e, R.string.image_process_failed_memory)
        } catch (e: Exception) {
            handleImageLoadError(e, null)
        }
    }

    /**
     * 处理图片加载错误
     */
    private fun handleImageLoadError(error: Throwable, errorStringRes: Int?) {
        Logger.e(TAG, "加载图片失败: ${error.javaClass.simpleName}", error)
        isImageLoaded = false
        val errorMessage = errorStringRes?.let { activity.getString(it) } ?: (error.message ?: "未知错误")
        ToastHelper.showLong(activity, R.string.image_load_failed, errorMessage)
        cancelCrop()
    }

    /**
     * 执行裁剪
     */
    fun performCrop() {
        try {
            if (!isImageLoaded) {
                ToastHelper.showShort(activity, R.string.image_loading_progress)
                return
            }

            val croppedBitmap = cropImageView?.getCroppedImage()
            if (croppedBitmap != null) {
                processCroppedBitmap(croppedBitmap)
            } else {
                ToastHelper.showShort(activity, R.string.image_crop_failed)
            }
        } catch (e: IllegalStateException) {
            handleCropError(e, R.string.error_state)
        } catch (e: IllegalArgumentException) {
            handleCropError(e, R.string.image_start_crop_argument_error)
        }
    }

    /**
     * 处理裁剪后的图片
     */
    private fun processCroppedBitmap(croppedBitmap: Bitmap) {
        // 通过ViewModel在后台线程处理图片，避免Activity持有协程引用
        viewModel.optimizeImage(
            croppedBitmap,
            onSuccess = { optimizedBitmap ->
                recycleOldBitmaps(croppedBitmap, optimizedBitmap)
                updateUIAfterCrop(optimizedBitmap)
                recognizeText(optimizedBitmap)
            },
            onError = { error ->
                handleImageOptimizationError(error)
            },
        )
    }

    /**
     * 处理裁剪错误
     */
    private fun handleCropError(error: Exception, defaultErrorRes: Int) {
        Logger.e(TAG, "裁剪图片失败: ${error.javaClass.simpleName}", error)
        val errorMessage = error.message ?: activity.getString(defaultErrorRes)
        ToastHelper.showShort(activity, R.string.image_crop_failed_with_error, errorMessage)
    }

    /**
     * 取消裁剪
     */
    fun cancelCrop() {
        isImageLoaded = false
        resetToInitialState()
        resetPhotoCardConstraints()
        // 取消裁剪时，如果是拍照的文件，删除临时文件
        deletePhotoFile()
    }

    /**
     * 重置到初始状态
     */
    private fun resetToInitialState() {
        cropContainer?.visibility = View.GONE
        cropButtonLayout?.visibility = View.GONE
        btnTakePhoto?.visibility = View.VISIBLE
        btnViewErrors?.visibility = View.VISIBLE
        btnErrorDictation?.visibility = View.VISIBLE
        placeholderLayout?.visibility = View.VISIBLE
        ivPhoto?.visibility = View.GONE
    }

    /**
     * 重置照片卡片约束
     */
    private fun resetPhotoCardConstraints() {
        val layoutParams = cardPhoto?.layoutParams as? ConstraintLayout.LayoutParams
        layoutParams?.let {
            it.bottomToTop = -1
            it.bottomToBottom = -1
            it.topToTop = -1
            it.bottomMargin = 0
            cardPhoto?.layoutParams = it
        }
    }

    /**
     * 图片理解：从图片中提取词语
     * 注意：此方法已被performCrop内部调用，通常不需要直接调用
     *
     * 边界情况处理：
     * - 检查网络连接（图片识别需要网络）
     * - 检查存储空间（保存识别结果需要存储）
     * - 检查空数据（识别结果为空）
     */
    fun recognizeText(bitmap: Bitmap) {
        // 检查网络连接
        if (!checkNetworkAvailability()) {
            return
        }

        // 检查存储空间
        if (!checkStorageSpace()) {
            return
        }

        // 获取识别模式并执行识别
        val mode = getRecognitionMode()
        viewModel.recognizeText(bitmap, mode)

        // 识别成功后，删除临时照片文件（如果是拍照的文件）
        // 注意：识别是异步的，但文件会在识别开始后删除，因为此时已经不再需要原始文件
        // 裁剪后的 Bitmap 已经加载到内存中，原始文件可以安全删除
        deletePhotoFile()
    }

    /**
     * 检查网络连接是否可用
     * @return true 如果网络可用，false 如果网络不可用（已处理错误）
     */
    private fun checkNetworkAvailability(): Boolean {
        if (NetworkUtils.isNetworkAvailable(activity)) {
            return true
        }

        val networkType = NetworkUtils.getNetworkType(activity)
        val errorMessage = "网络不可用（当前：$networkType），图片识别需要网络连接，请检查网络设置后重试"
        handleRecognitionPreconditionError(AppErrorKind.NetworkOrApi, errorMessage)
        return false
    }

    /**
     * 检查存储空间是否充足
     * @return true 如果存储空间充足，false 如果存储空间不足（已处理错误）
     */
    private fun checkStorageSpace(): Boolean {
        val storageError = StorageUtils.getStorageSpaceErrorMessage(activity)
        if (storageError == null) {
            return true
        }

        handleRecognitionPreconditionError(AppErrorKind.StorageSpaceFull, storageError)
        return false
    }

    /**
     * 处理识别前置条件错误（网络、存储空间等）
     */
    private fun handleRecognitionPreconditionError(errorKind: AppErrorKind, errorMessage: String) {
        val error = AppError(errorKind, errorMessage)
        onImageRecognitionError(error)
        ToastHelper.showLong(activity, errorMessage)
    }

    /**
     * 根据听写类型获取识别模式
     * @return 识别模式
     */
    private fun getRecognitionMode(): ImageUnderstandingService.ImageUnderstandingMode {
        return if (dictationType == AppConfig.TYPE_CHINESE) {
            ImageUnderstandingService.ImageUnderstandingMode.CHINESE_DICTATION
        } else {
            ImageUnderstandingService.ImageUnderstandingMode.ENGLISH_DICTATION
        }
    }

    /**
     * 显示手动输入对话框
     * 使用 ViewModel 统一处理，与图片识别流程保持一致
     * @param onManualInput 手动输入回调（已废弃，保留用于向后兼容，实际通过 ViewModel 事件处理）
     */
    fun showManualInputDialog(onManualInput: (List<String>) -> Unit) {
        val inputEditText =
            android.widget.EditText(activity).apply {
                hint = "请输入词语，每行一个词语"
                minLines = MANUAL_INPUT_MIN_LINES
                maxLines = MANUAL_INPUT_MAX_LINES
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
                    showManualInputDialog(onManualInput)
                    return@setPositiveButton
                }

                // 通过 ViewModel 更新状态并发送事件，与图片识别流程保持一致
                // 事件会通过 DictationStateObserver 处理，最终调用 onImageUnderstandingSuccess
                viewModel.setManualInputWords(parsedWords)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 从输入文本中解析词语
     */
    private fun parseWordsFromInput(input: String): List<String> {
        return input.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 删除临时照片文件
     * 在图片识别成功后或取消裁剪时调用，清理磁盘空间
     */
    private fun deletePhotoFile() {
        currentPhotoFile?.let { file ->
            try {
                if (file.exists() && file.delete()) {
                    Logger.d(TAG, "已删除临时照片文件: ${file.absolutePath}")
                } else {
                    Logger.w(TAG, "删除临时照片文件失败或文件不存在: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "删除临时照片文件时发生异常: ${file.absolutePath}", e)
            } finally {
                currentPhotoFile = null
            }
        }
    }

    /**
     * 回收旧的 Bitmap 资源
     */
    private fun recycleOldBitmaps(croppedBitmap: Bitmap, optimizedBitmap: Bitmap) {
        // 回收原始裁剪的 Bitmap（如果已优化）
        if (optimizedBitmap !== croppedBitmap) {
            ImageProcessingHelper.recycleBitmap(croppedBitmap)
        }

        // 回收 ImageView 中旧的 Bitmap
        val oldDrawable = ivPhoto?.drawable
        if (oldDrawable is android.graphics.drawable.BitmapDrawable) {
            val oldBitmap = oldDrawable.bitmap
            if (oldBitmap != optimizedBitmap) {
                ImageProcessingHelper.recycleBitmap(oldBitmap)
            }
        }
    }

    /**
     * 更新裁剪后的 UI 状态
     */
    private fun updateUIAfterCrop(optimizedBitmap: Bitmap) {
        isImageLoaded = false
        hideCropUI()
        showPhotoView(optimizedBitmap)
        restorePhotoCardConstraints()
    }

    /**
     * 隐藏裁剪UI
     */
    private fun hideCropUI() {
        cropContainer?.visibility = View.GONE
        cropButtonLayout?.visibility = View.GONE
        btnTakePhoto?.visibility = View.GONE
        btnViewErrors?.visibility = View.GONE
        btnErrorDictation?.visibility = View.GONE
    }

    /**
     * 显示照片视图
     */
    private fun showPhotoView(bitmap: Bitmap) {
        ivPhoto?.visibility = View.VISIBLE
        ivPhoto?.setImageBitmap(bitmap)
        placeholderLayout?.visibility = View.GONE
    }

    /**
     * 处理图片优化错误
     */
    private fun handleImageOptimizationError(error: Throwable) {
        val (logMessage, errorMessageRes) = when (error) {
            is IOException -> "IO错误，处理裁剪图片失败" to R.string.image_process_failed_file
            is OutOfMemoryError -> "内存不足，处理裁剪图片失败" to R.string.image_process_failed_memory
            is IllegalStateException -> "状态错误，处理裁剪图片失败" to R.string.error_state
            else -> "处理裁剪图片失败" to R.string.error_unknown
        }

        Logger.e(TAG, logMessage, error)
        val errorMessage = error.message ?: activity.getString(errorMessageRes)
        ToastHelper.showShort(activity, R.string.image_process_failed, errorMessage)
    }

    private fun restorePhotoCardConstraints() {
        val layoutParams = cardPhoto?.layoutParams as? ConstraintLayout.LayoutParams
        layoutParams?.let {
            cardAnswers?.let { answersCard ->
                it.bottomToTop = answersCard.id
            } ?: run {
                it.bottomToTop = -1
            }
            it.bottomToBottom = -1
            cardProgress?.let { progressCard ->
                it.topToBottom = progressCard.id
            } ?: run {
                it.topToTop = -1
            }
            it.topToTop = -1
            it.bottomMargin = 0
            cardPhoto?.layoutParams = it
        }
    }
}
