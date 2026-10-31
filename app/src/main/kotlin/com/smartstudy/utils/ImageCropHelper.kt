package com.smartstudy.utils

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.canhub.cropper.CropImageView
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.ImageProcessingHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 图片裁剪辅助类
 *
 * 提取图片裁剪的公共逻辑，避免在多个Activity中重复代码。
 *
 * 功能特性：
 * - 统一的图片裁剪流程
 * - 自动图片优化和内存管理
 * - 支持动态布局调整
 * - 生命周期感知（使用lifecycleScope）
 *
 * 使用示例：
 * ```
 * val cropHelper = ImageCropHelper(
 *     activity = this,
 *     cropImageView = cropImageView,
 *     cropContainer = cropContainer,
 *     ivPhoto = ivPhoto,
 *     onCropComplete = { bitmap -> processImage(bitmap) }
 * )
 * cropHelper.initCropViews(btnCropDone, btnCropCancel)
 * cropHelper.startCropImage(imageUri, cardPhoto, ...)
 * ```
 *
 * @param activity Activity实例
 * @param coroutineScope 协程作用域，用于执行异步操作（应使用viewModelScope，避免Activity持有协程引用）
 * @param cropImageView 裁剪视图
 * @param cropContainer 裁剪容器
 * @param ivPhoto 图片显示视图
 * @param onCropComplete 裁剪完成回调，参数为裁剪后的Bitmap
 */
class ImageCropHelper(
    private val activity: AppCompatActivity,
    private val coroutineScope: CoroutineScope,
    private val cropImageView: CropImageView,
    private val cropContainer: View,
    private val ivPhoto: ImageView,
    private val onCropComplete: (Bitmap) -> Unit,
) {
    companion object {
        private const val TAG = "ImageCropHelper"
    }

    private var isImageLoaded = false
    private var cropButtonLayout: View? = null

    /**
     * 初始化裁剪视图
     */
    fun initCropViews(
        btnCropDone: android.widget.Button,
        btnCropCancel: android.widget.Button,
    ) {
        cropImageView.apply {
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

        btnCropDone.setOnClickListener {
            performCrop()
        }

        btnCropCancel.setOnClickListener {
            cancelCrop()
        }
    }

    /**
     * 开始裁剪图片
     */
    fun startCropImage(
        imageUri: Uri,
        cardPhoto: androidx.cardview.widget.CardView? = null,
        placeholderLayout: View? = null,
        btnTakePhoto: View? = null,
        btnViewErrors: View? = null,
        btnErrorDictation: View? = null,
        cropButtonLayout: View? = null,
        cardProgressId: Int? = null,
        @Suppress("UNUSED_PARAMETER") cardAnswersId: Int? = null,
    ) {
        try {
            Logger.d(TAG, "开始裁剪图片: $imageUri")

            // 保存 cropButtonLayout 以便在其他方法中使用
            this.cropButtonLayout = cropButtonLayout

            cardPhoto?.visibility = View.VISIBLE
            ivPhoto.visibility = View.GONE
            placeholderLayout?.visibility = View.GONE
            cropContainer.visibility = View.VISIBLE
            btnTakePhoto?.visibility = View.GONE
            btnViewErrors?.visibility = View.GONE
            btnErrorDictation?.visibility = View.GONE
            cropButtonLayout?.visibility = View.VISIBLE

            // 动态调整约束
            val layoutParams = cardPhoto?.layoutParams as? ConstraintLayout.LayoutParams
            layoutParams?.let {
                it.bottomToTop = -1
                it.bottomToBottom = 0
                cardProgressId?.let { id -> it.topToBottom = id }
                it.topToTop = -1
                it.bottomMargin = activity.resources.getDimensionPixelSize(com.smartstudy.R.dimen.spacing_xl)
                cardPhoto.layoutParams = it
            }

            isImageLoaded = false

            cropContainer.post {
                try {
                    cropImageView.setImageBitmap(null)

                    // 先加载 Bitmap 并处理 EXIF orientation，保持拍照时的原始方向
                    val context = activity.applicationContext
                    val bitmap = ImageProcessingHelper.loadBitmapWithOrientation(imageUri, context)

                    if (bitmap != null) {
                        // 使用处理后的 Bitmap 设置到 CropImageView
                        cropImageView.setImageBitmap(bitmap)
                        isImageLoaded = true
                        Logger.d(TAG, "图片加载成功，已处理 EXIF orientation")
                    } else {
                        throw IOException("无法加载图片")
                    }
                } catch (e: IllegalArgumentException) {
                    Logger.e(TAG, "参数错误，加载裁剪图片时出错", e)
                    isImageLoaded = false
                    ToastHelper.showLong(activity, R.string.image_load_failed, activity.getString(R.string.image_uri_invalid))
                    cancelCrop()
                } catch (e: IOException) {
                    Logger.e(TAG, "IO错误，加载裁剪图片时出错", e)
                    isImageLoaded = false
                    ToastHelper.showLong(activity, R.string.image_load_failed, e.message ?: activity.getString(R.string.image_file_read_failed))
                    cancelCrop()
                } catch (e: OutOfMemoryError) {
                    Logger.e(TAG, "内存不足，加载裁剪图片失败", e)
                    isImageLoaded = false
                    ToastHelper.showLong(activity, R.string.image_load_failed, activity.getString(R.string.image_process_failed_memory))
                    cancelCrop()
                } catch (e: Exception) {
                    Logger.e(TAG, "加载图片时发生未知错误", e)
                    isImageLoaded = false
                    ToastHelper.showLong(activity, R.string.image_load_failed, e.message ?: "未知错误")
                    cancelCrop()
                }
            }
        } catch (e: SecurityException) {
            Logger.e(TAG, "权限不足，无法开始裁剪图片", e)
            ToastHelper.showShort(activity, R.string.image_start_crop_failed, activity.getString(R.string.error_permission_denied))
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，无法开始裁剪图片", e)
            ToastHelper.showShort(activity, R.string.image_start_crop_failed, e.message ?: activity.getString(R.string.error_state))
        }
    }

    /**
     * 执行裁剪
     */
    private fun performCrop() {
        try {
            if (!isImageLoaded) {
                ToastHelper.showShort(activity, R.string.image_loading_progress)
                return
            }

            val croppedBitmap = cropImageView.getCroppedImage()
            if (croppedBitmap != null && !croppedBitmap.isRecycled) {
                coroutineScope.launch {
                    try {
                        val optimizedBitmap =
                            withContext(Dispatchers.IO) {
                                ImageProcessingHelper.optimizeBitmapForRecognition(croppedBitmap)
                            }

                        // 回收原始裁剪的 Bitmap（如果已优化）
                        if (optimizedBitmap !== croppedBitmap) {
                            ImageProcessingHelper.recycleBitmap(croppedBitmap)
                        }

                        // 回收 ImageView 中旧的 Bitmap
                        val oldDrawable = ivPhoto.drawable
                        if (oldDrawable is android.graphics.drawable.BitmapDrawable) {
                            val oldBitmap = oldDrawable.bitmap
                            if (oldBitmap != optimizedBitmap) {
                                ImageProcessingHelper.recycleBitmap(oldBitmap)
                            }
                        }

                        isImageLoaded = false
                        cropContainer.visibility = View.GONE
                        cropButtonLayout?.visibility = View.GONE
                        ivPhoto.visibility = View.VISIBLE

                        activity.runOnUiThread {
                            ivPhoto.setImageBitmap(optimizedBitmap)
                            onCropComplete(optimizedBitmap)
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "处理裁剪图片失败", e)
                        activity.runOnUiThread {
                            ToastHelper.showLong(activity, R.string.image_process_failed, e.message ?: "未知错误")
                        }
                    }
                }
            } else {
                ToastHelper.showShort(activity, R.string.image_crop_failed_simple)
            }
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，裁剪图片失败", e)
            ToastHelper.showShort(activity, R.string.image_crop_failed, e.message ?: activity.getString(R.string.error_state))
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "参数错误，裁剪图片失败", e)
            ToastHelper.showShort(activity, R.string.image_crop_failed, e.message ?: activity.getString(R.string.error_argument))
        }
    }

    /**
     * 取消裁剪
     */
    private fun cancelCrop() {
        cropContainer.visibility = View.GONE
        cropButtonLayout?.visibility = View.GONE
        ivPhoto.visibility = View.GONE
        isImageLoaded = false
    }
}
