package com.smartstudy.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.smartstudy.R
import java.util.Random

/**
 * 对话框工具类
 * 提取重复的对话框创建逻辑，提供统一的对话框创建接口
 *
 * 功能特性：
 * - 统一的对话框创建接口
 * - 简化对话框使用代码
 * - 支持多种常用对话框类型（确认、信息、列表选择、输入等）
 *
 * 使用示例：
 * ```
 * // 确认对话框
 * DialogHelper.createConfirmDialog(
 *     context = this,
 *     title = "确认",
 *     message = "确定要删除吗？",
 *     onPositive = { /* 确认操作 */ }
 * ).show()
 *
 * // 列表选择对话框
 * DialogHelper.createListDialog(
 *     context = this,
 *     title = "选择操作",
 *     items = arrayOf("选项1", "选项2", "选项3"),
 *     onItemSelected = { index -> /* 处理选择 */ }
 * ).show()
 * ```
 */
object DialogHelper {
    /**
     * 创建简单的确认对话框
     *
     * @param context Context
     * @param title 对话框标题
     * @param message 对话框消息
     * @param positiveText 确认按钮文本，默认为"确定"
     * @param negativeText 取消按钮文本，默认为"取消"
     * @param onPositive 确认按钮点击回调
     * @param onNegative 取消按钮点击回调
     * @return 创建的AlertDialog
     */
    fun createConfirmDialog(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
    ): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onPositive?.invoke() }
            .setNegativeButton(negativeText) { _, _ -> onNegative?.invoke() }
            .create()
    }

    /**
     * 创建简单的信息对话框
     *
     * @param context Context
     * @param title 对话框标题
     * @param message 对话框消息
     * @param buttonText 按钮文本，默认为"知道了"
     * @param onDismiss 对话框关闭回调
     * @return 创建的AlertDialog
     */
    fun createInfoDialog(
        context: Context,
        title: String,
        message: String,
        buttonText: String = "知道了",
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(buttonText) { _, _ -> }
            .setOnDismissListener { onDismiss?.invoke() }
            .create()
    }

    /**
     * 创建列表选择对话框
     *
     * @param context Context
     * @param title 对话框标题
     * @param items 选项列表
     * @param onItemSelected 选项选择回调，参数为选中项的索引
     * @param negativeText 取消按钮文本，默认为"取消"
     * @return 创建的AlertDialog
     */
    fun createListDialog(
        context: Context,
        title: String,
        items: Array<String>,
        onItemSelected: (Int) -> Unit,
        negativeText: String = "取消",
    ): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which -> onItemSelected(which) }
            .setNegativeButton(negativeText, null)
            .create()
    }

    /**
     * 创建错误对话框
     *
     * @param context Context
     * @param title 对话框标题，默认为"错误"
     * @param message 错误消息
     * @param buttonText 按钮文本，默认为"知道了"
     * @param onDismiss 对话框关闭回调
     * @return 创建的AlertDialog
     */
    fun createErrorDialog(
        context: Context,
        title: String = "错误",
        message: String,
        buttonText: String = "知道了",
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        return createInfoDialog(context, title, message, buttonText, onDismiss)
    }

    /**
     * 创建输入对话框
     *
     * @param context Context
     * @param title 对话框标题
     * @param hint 输入框提示文本
     * @param initialText 初始文本
     * @param minLines 最小行数
     * @param maxLines 最大行数
     * @param onInput 输入确认回调，参数为输入的文本
     * @param positiveText 确认按钮文本，默认为"确定"
     * @param negativeText 取消按钮文本，默认为"取消"
     * @return 创建的AlertDialog
     */
    fun createInputDialog(
        context: Context,
        title: String,
        hint: String = "",
        initialText: String = "",
        minLines: Int = 1,
        maxLines: Int = 1,
        onInput: (String) -> Unit,
        positiveText: String = "确定",
        negativeText: String = "取消",
    ): AlertDialog {
        val inputEditText =
            EditText(context).apply {
                this.hint = hint
                this.minLines = minLines
                this.maxLines = maxLines
                setText(initialText)
            }

        return AlertDialog.Builder(context)
            .setTitle(title)
            .setView(inputEditText)
            .setPositiveButton(positiveText) { _, _ ->
                val text = inputEditText.text.toString()
                onInput(text)
            }
            .setNegativeButton(negativeText, null)
            .create()
    }

    /**
     * 显示删除验证对话框（算术题验证）
     * 用于防止误删操作，需要用户完成简单的算术题验证
     *
     * @param context Context
     * @param onConfirm 验证通过后的确认回调
     * @return 创建的AlertDialog
     *
     * 使用示例：
     * ```
     * DialogHelper.showDeleteVerificationDialog(this) {
     *     // 验证通过后执行删除操作
     *     deleteSelectedItems()
     * }.show()
     * ```
     */
    fun showDeleteVerificationDialog(
        context: Context,
        onConfirm: () -> Unit,
    ): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_delete_verification, null)
        val tvQuestion = dialogView.findViewById<TextView>(R.id.tvQuestion)
        val etAnswer = dialogView.findViewById<EditText>(R.id.etAnswer)
        val tvError = dialogView.findViewById<TextView>(R.id.tvError)

        // 生成10以内加减法
        val (question, correctAnswer) = generateVerificationQuestion()
        tvQuestion.text = question

        val dialog =
            AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(true)
                .create()

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val userAnswer = etAnswer.text.toString().trim().toIntOrNull()

            if (userAnswer == null) {
                tvError.text = context.getString(R.string.error_word_list_delete_dialog_input_hint)
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (userAnswer == correctAnswer) {
                dialog.dismiss()
                onConfirm()
            } else {
                tvError.text = context.getString(R.string.error_word_list_delete_dialog_wrong_answer)
                tvError.visibility = View.VISIBLE
                etAnswer.text.clear()
            }
        }

        return dialog
    }

    /**
     * 生成验证题目（10以内加减法）
     * 用于删除验证对话框，生成简单的算术题
     *
     * @return Pair<题目文本, 正确答案>
     */
    private fun generateVerificationQuestion(): Pair<String, Int> {
        val random = Random()
        val num1 = random.nextInt(11) // 0-10
        val num2 = random.nextInt(11) // 0-10
        val isAddition = random.nextBoolean()

        return if (isAddition) {
            Pair("$num1 + $num2 = ?", num1 + num2)
        } else {
            // 确保减法结果非负
            val maxNum = maxOf(num1, num2)
            val minNum = minOf(num1, num2)
            Pair("$maxNum - $minNum = ?", maxNum - minNum)
        }
    }
}
