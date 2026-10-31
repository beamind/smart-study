package com.smartstudy.english.handlers

import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageView
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.ImageProcessingHelper
import com.smartstudy.english.ErrorBookViewModel
import com.smartstudy.english.QuestionAnswer
import com.smartstudy.english.managers.ErrorBookImageRecognitionManager
import com.smartstudy.utils.showLongToast
import com.smartstudy.utils.showToast
import java.io.File
import java.io.IOException

/**
 * 图片处理Handler的UI回调接口
 * 用于与Activity通信，保持解耦
 */
interface ImageHandlerUICallback {
    /**
     * 识别成功回调
     * @param questions 识别到的题目列表
     * @param bitmap 用于识别的图片（可选，用于第二阶段下划线识别）
     */
    fun onRecognitionSuccess(
        questions: List<QuestionAnswer>,
        bitmap: Bitmap? = null,
    )

    /**
     * 识别错误回调
     * @param error 错误信息
     */
    fun onRecognitionError(error: String)

    /**
     * 显示进度对话框
     * @param message 提示信息
     */
    fun showProgressDialog(message: String)

    /**
     * 隐藏进度对话框
     */
    fun hideProgressDialog()

    /**
     * 更新UI为选择状态（识别成功后显示题目列表）
     */
    fun updateUIForSelectionState()

    /**
     * 更新UI为错误状态（识别失败后恢复操作按钮）
     */
    fun updateUIForErrorState()
}

/**
 * 错题本图片处理管理器
 *
 * 负责错题本图片选择、裁剪、识别等逻辑，将图片处理相关功能从Activity中分离出来，
 * 提高代码的可维护性和可测试性。
 *
 * 功能特性：
 * - 支持拍照、相册选择两种方式
 * - 自动图片优化和内存管理
 * - 统一的错误处理
 * - 生命周期感知（使用lifecycleScope）
 * - 识别进度管理
 *
 * 使用示例：
 * ```
 * val imageManager = ErrorBookImageManager(
 *     activity = this,
 *     imageUnderstandingService = imageUnderstandingService,
 *     uiCallback = this // Activity实现ImageHandlerUICallback接口
 * )
 * imageManager.initCropViews()
 * imageManager.showImageSourceDialog()
 * ```
 *
 * @param activity Activity实例，用于访问UI
 * @param viewModel ViewModel实例，用于执行异步操作
 * @param imageUnderstandingService 图片理解服务
 * @param uiCallback UI回调接口，用于与Activity通信
 */
class ErrorBookImageManager(
    private val activity: AppCompatActivity,
    private val viewModel: ErrorBookViewModel,
    private val imageUnderstandingService: com.smartstudy.dictation.core.ImageUnderstandingService,
    private val uiCallback: ImageHandlerUICallback,
) {
    companion object {
        private const val TAG = "ErrorBookImageManager"
    }

    // 视图组件
    var ivPhoto: ImageView? = null
    var cropImageView: CropImageView? = null
    var cropContainer: View? = null
    var btnCropDone: Button? = null
    var btnCropCancel: Button? = null
    var cropButtonLayout: View? = null
    var cardPhoto: CardView? = null
    var cardActions: CardView? = null

    // 状态
    private var isImageLoaded = false
    private var currentPhotoUri: Uri? = null
    private var photoFile: File? = null

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
                        activity.showLongToast(activity.getString(com.smartstudy.R.string.image_load_failed, error.message ?: ""))
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
                ),
            ) { _, which ->
                when (which) {
                    0 -> takePhoto()
                    1 -> pickImageFromGallery()
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
            photoFile?.let { file ->
                val photoUri =
                    FileProvider.getUriForFile(
                        activity,
                        "${activity.applicationContext.packageName}.fileprovider",
                        file,
                    )
                currentPhotoUri = photoUri
                takePictureLauncher.launch(photoUri)
            } ?: run {
                handlePhotoCreationError("文件创建失败")
            }
        } catch (e: IllegalStateException) {
            handlePhotoCreationError(e.message ?: activity.getString(com.smartstudy.R.string.error_state))
        }
    }

    /**
     * 处理拍照创建错误
     */
    private fun handlePhotoCreationError(errorMessage: String) {
        Logger.e(TAG, "创建图片文件失败: $errorMessage")
        clearPhotoReferences()
        activity.showToast(activity.getString(com.smartstudy.R.string.photo_failed_with_error, errorMessage))
    }

    /**
     * 清理照片引用
     */
    private fun clearPhotoReferences() {
        currentPhotoUri = null
        photoFile = null
    }

    /**
     * 创建图片文件
     */
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val imageFileName = "error_book_${System.currentTimeMillis()}"
        val storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    /**
     * 开始裁剪图片
     */
    fun startCropImage(imageUri: Uri) {
        try {
            Logger.d(TAG, "开始裁剪图片: $imageUri")

            // 隐藏操作按钮卡片，显示照片卡片
            cardActions?.visibility = View.GONE
            cardPhoto?.visibility = View.VISIBLE

            // 隐藏照片显示，显示裁剪视图
            ivPhoto?.visibility = View.GONE
            cropContainer?.visibility = View.VISIBLE
            cropButtonLayout?.visibility = View.VISIBLE

            // 重置图片加载状态
            isImageLoaded = false

            // 确保视图可见后再加载图片（延迟一小段时间确保布局完成）
            cropContainer?.post {
                try {
                    // 清除之前的图片状态（如果有）
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
                    handleImageLoadError(e, "图片URI无效")
                } catch (e: IOException) {
                    handleImageLoadError(e, "文件读取失败")
                } catch (e: Exception) {
                    handleImageLoadError(e, e.message ?: "未知错误")
                }
            }
        } catch (e: SecurityException) {
            Logger.e(TAG, "权限不足，开始裁剪图片失败", e)
            activity.showToast(activity.getString(com.smartstudy.R.string.image_start_crop_failed_with_error, activity.getString(com.smartstudy.R.string.error_permission_denied)))
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，开始裁剪图片失败", e)
            activity.showToast(activity.getString(com.smartstudy.R.string.image_start_crop_failed_with_error, e.message ?: activity.getString(com.smartstudy.R.string.error_state)))
        }
    }

    /**
     * 执行裁剪
     */
    fun performCrop() {
        try {
            // 检查图片是否已加载完成
            if (!isImageLoaded) {
                activity.showToast(com.smartstudy.R.string.image_loading_progress)
                return
            }

            val croppedBitmap = cropImageView?.getCroppedImage()
            if (croppedBitmap != null && !croppedBitmap.isRecycled) {
                // 通过ViewModel在后台线程处理图片，避免Activity持有协程引用
                viewModel.optimizeImage(
                    croppedBitmap,
                    onSuccess = { optimizedBitmap ->
                        handleCropSuccess(croppedBitmap, optimizedBitmap)
                    },
                    onError = { error ->
                        handleImageOptimizationError(error)
                    },
                )
            } else {
                activity.showToast(com.smartstudy.R.string.image_crop_failed_simple)
            }
        } catch (e: IllegalStateException) {
            handleCropError(e, com.smartstudy.R.string.error_state)
        } catch (e: IllegalArgumentException) {
            handleCropError(e, com.smartstudy.R.string.error_argument)
        }
    }

    /**
     * 处理裁剪错误
     */
    private fun handleCropError(error: Exception, defaultErrorRes: Int) {
        Logger.e(TAG, "裁剪图片失败: ${error.javaClass.simpleName}", error)
        val errorMessage = error.message ?: activity.getString(defaultErrorRes)
        activity.showToast(activity.getString(com.smartstudy.R.string.image_crop_failed_with_error, errorMessage))
    }

    /**
     * 处理图片加载错误
     */
    private fun handleImageLoadError(error: Throwable, errorMessage: String) {
        Logger.e(TAG, "加载图片失败: ${error.javaClass.simpleName}", error)
        isImageLoaded = false
        activity.showToast(activity.getString(com.smartstudy.R.string.image_load_failed, errorMessage))
        cancelCrop()
    }

    /**
     * 取消裁剪
     */
    fun cancelCrop() {
        isImageLoaded = false
        resetToInitialState()
        // 取消裁剪时，如果是拍照的文件，删除临时文件
        deletePhotoFile()
    }

    /**
     * 重置到初始状态
     */
    private fun resetToInitialState() {
        // 隐藏照片卡片，恢复操作按钮
        cardPhoto?.visibility = View.GONE
        cardActions?.visibility = View.VISIBLE

        // 隐藏裁剪视图
        cropContainer?.visibility = View.GONE
        cropButtonLayout?.visibility = View.GONE
        ivPhoto?.visibility = View.GONE
    }

    /**
     * 识别错题文本（从Bitmap）
     * 显示进度对话框，并在识别完成后通过回调处理结果
     */
    /**
     * 处理裁剪成功
     */
    private fun handleCropSuccess(
        croppedBitmap: Bitmap,
        optimizedBitmap: Bitmap,
    ) {
        recycleOldBitmaps(croppedBitmap, optimizedBitmap)
        updateUIAfterCrop(optimizedBitmap)
        recognizeErrorBookText(optimizedBitmap)
    }

    /**
     * 回收旧的 Bitmap 资源
     */
    private fun recycleOldBitmaps(
        croppedBitmap: Bitmap,
        optimizedBitmap: Bitmap,
    ) {
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
    }

    /**
     * 隐藏裁剪UI
     */
    private fun hideCropUI() {
        cropContainer?.visibility = View.GONE
        cropButtonLayout?.visibility = View.GONE
    }

    /**
     * 显示照片视图
     */
    private fun showPhotoView(bitmap: Bitmap) {
        ivPhoto?.visibility = View.VISIBLE
        ivPhoto?.setImageBitmap(bitmap)
    }

    /**
     * 处理图片优化错误
     */
    private fun handleImageOptimizationError(error: Throwable) {
        when (error) {
            is IOException -> {
                Logger.e(TAG, "IO错误，处理裁剪图片失败", error)
                activity.showToast(activity.getString(com.smartstudy.R.string.image_process_failed_with_error, "文件处理失败"))
            }
            is OutOfMemoryError -> {
                Logger.e(TAG, "内存不足，处理裁剪图片失败", error)
                activity.showToast(activity.getString(com.smartstudy.R.string.image_process_failed_with_error, "内存不足"))
            }
            else -> {
                Logger.e(TAG, "处理裁剪图片失败", error)
                activity.showToast(activity.getString(com.smartstudy.R.string.image_process_failed_with_error, error.message ?: "未知错误"))
            }
        }
    }

    /**
     * 删除临时照片文件
     * 在图片识别成功后或取消裁剪时调用，清理磁盘空间
     */
    private fun deletePhotoFile() {
        photoFile?.let { file ->
            try {
                if (file.exists() && file.delete()) {
                    Logger.d(TAG, "已删除临时照片文件: ${file.absolutePath}")
                } else {
                    Logger.w(TAG, "删除临时照片文件失败或文件不存在: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "删除临时照片文件时发生异常: ${file.absolutePath}", e)
            } finally {
                photoFile = null
            }
        }
    }

    private fun recognizeErrorBookText(bitmap: Bitmap) {
        // 显示识别进度对话框
        uiCallback.showProgressDialog("正在识别中，请稍候...")

        // 创建新的识别Handler，使用内部回调处理结果
        val recognitionHandler =
            ErrorBookImageRecognitionManager(
                viewModel = viewModel,
                imageUnderstandingService = imageUnderstandingService,
                onRecognitionSuccess = { questions ->
                    // 隐藏进度对话框
                    uiCallback.hideProgressDialog()
                    // 更新UI为选择状态
                    uiCallback.updateUIForSelectionState()
                    // 通知识别成功，传递bitmap用于第二阶段识别
                    uiCallback.onRecognitionSuccess(questions, bitmap)
                },
                onRecognitionError = { error ->
                    // 隐藏进度对话框
                    uiCallback.hideProgressDialog()
                    // 更新UI为错误状态
                    uiCallback.updateUIForErrorState()
                    // 通知识别错误
                    uiCallback.onRecognitionError(error)
                },
            )

        // 开始识别
        recognitionHandler.recognizeErrorBookText(bitmap)
    }
}
