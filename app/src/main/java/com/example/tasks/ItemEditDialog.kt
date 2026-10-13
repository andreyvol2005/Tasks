package com.example.tasks

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.example.tasks.databinding.DialogItemEditBinding
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Одна форма и для добавления, и для редактирования - раньше это были два разных
 * оверлея, а для покупок редактирование вообще было недоступно.
 *
 * Набор полей зависит от вида списка: задачам показываем важность и срок,
 * покупкам - количество и цену.
 */
class ItemEditDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val kind = CategoryKind.fromDbValue(args.getString(ARG_KIND) ?: CategoryKind.TASKS.dbValue)
        val isEdit = args.getBoolean(ARG_IS_EDIT)
        val b = DialogItemEditBinding.inflate(layoutInflater)

        b.textInput.setText(args.getString(ARG_TEXT).orEmpty())
        b.textInput.setSelection(b.textInput.text?.length ?: 0)

        b.taskBlock.visibility = if (kind == CategoryKind.TASKS) android.view.View.VISIBLE else android.view.View.GONE
        b.shoppingBlock.visibility =
            if (kind == CategoryKind.SHOPPING) android.view.View.VISIBLE else android.view.View.GONE

        setupPriority(b, args.getInt(ARG_PRIORITY, Priority.NORMAL))
        setupDueDate(b, kind, args.getLong(ARG_DUE_AT, NO_DUE).takeIf { it != NO_DUE })
        setupShopping(b, args.getString(ARG_QUANTITY), args.getLong(ARG_PRICE_CENTS, NO_PRICE).takeIf { it != NO_PRICE })

        b.textInput.doAfterTextChanged { b.textInputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isEdit) R.string.dialog_edit_title else R.string.dialog_add_title)
            .setView(b.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(if (isEdit) R.string.action_save else R.string.action_add, null)
            .create()

        dialog.setOnShowListener {
            // Кнопку намеренно перехватываем: стандартный обработчик закрывает диалог
            // всегда, а нам нужно не закрывать его на пустом тексте.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submit(b, kind, args.getLong(ARG_ID, 0L))
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            b.textInput.requestFocus()
        }
        return dialog
    }

    private fun setupPriority(b: DialogItemEditBinding, current: Int) {
        val chip = when (current) {
            Priority.URGENT -> b.priorityUrgent
            Priority.HIGH -> b.priorityHigh
            else -> b.priorityNormal
        }
        chip.isChecked = true
    }

    private fun selectedPriority(b: DialogItemEditBinding): Int = when (b.priorityGroup.checkedChipId) {
        b.priorityUrgent.id -> Priority.URGENT
        b.priorityHigh.id -> Priority.HIGH
        else -> Priority.NORMAL
    }

    private fun setupDueDate(b: DialogItemEditBinding, kind: CategoryKind, current: Long?) {
        if (kind != CategoryKind.TASKS) return

        b.dueDateButton.setOnClickListener {
            val constraints = CalendarConstraints.Builder()
                .setValidator(DateValidatorPointForward.from(MaterialDatePicker.todayInUtcMilliseconds()))
                .build()
            MaterialDatePicker.Builder.datePicker()
                .setCalendarConstraints(constraints)
                .setSelection(current?.let { toUtcMidnight(it) } ?: MaterialDatePicker.todayInUtcMilliseconds())
                .build()
                .apply {
                    addOnPositiveButtonClickListener { utcMillis ->
                        // Пикер отдаёт полночь UTC, а храним мы полночь по местному
                        // времени - иначе срок съезжает на сутки у всех, кто не в UTC.
                        val date = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
                        val millis = date.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        b.dueDateText.text = date.format(DisplayFormats.dueDate)
                        b.dueDateButton.setText(R.string.dialog_due_date_clear)
                        b.dueDateButton.tag = millis
                    }
                }
                .show(parentFragmentManager, TAG_DUE_DATE)
        }

        if (current != null) {
            b.dueDateText.text = Instant.ofEpochMilli(current).atZone(ZoneId.systemDefault())
                .toLocalDate().format(DisplayFormats.dueDate)
            b.dueDateButton.setText(R.string.dialog_due_date_clear)
            b.dueDateButton.tag = current
        }
    }

    private fun toUtcMidnight(localMillis: Long): Long =
        Instant.ofEpochMilli(localMillis).atZone(ZoneId.systemDefault())
            .toLocalDate().atStartOfDay().atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun setupShopping(b: DialogItemEditBinding, quantity: String?, priceCents: Long?) {
        if (quantity != null) b.quantityInput.setText(quantity)
        if (priceCents != null) b.priceInput.setText(formatPriceForInput(priceCents))
    }

    private fun formatPriceForInput(cents: Long): String =
        if (cents % 100L == 0L) (cents / 100L).toString()
        else String.format(Locale.getDefault(), "%.2f", cents / 100.0)

    private fun submit(b: DialogItemEditBinding, kind: CategoryKind, itemId: Long) {
        val text = b.textInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            b.textInputLayout.error = getString(R.string.dialog_error_empty)
            return
        }
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putLong(ARG_RESULT_ITEM_ID, itemId)
                putString(ARG_RESULT_TEXT, text)
                putInt(ARG_RESULT_PRIORITY, selectedPriority(b))
                putLong(ARG_RESULT_DUE_AT, (b.dueDateButton.tag as? Long) ?: NO_DUE)
                putString(ARG_RESULT_QUANTITY, b.quantityInput.text?.toString()?.trim().orEmpty())
                putLong(ARG_RESULT_PRICE_CENTS, parsePrice(b.priceInput.text?.toString()) ?: NO_PRICE)
                putBoolean(ARG_RESULT_IS_SHOPPING, kind == CategoryKind.SHOPPING)
            }
        )
        dismiss()
    }

    /** «125» -> 12500, «99,9» -> 9990. Возвращает null, если введено не число. */
    private fun parsePrice(raw: String?): Long? {
        val normalized = raw?.trim()?.replace(',', '.')?.replace(" ", "")?.takeIf { it.isNotEmpty() }
            ?: return null
        val value = normalized.toDoubleOrNull() ?: return null
        if (value < 0) return null
        return (value * 100).roundToLong()
    }

    companion object {
        const val REQUEST_KEY = "item_edit_result"
        const val NO_DUE = -1L
        const val NO_PRICE = -1L

        private const val TAG_DUE_DATE = "dueDatePicker"
        private const val ARG_KIND = "kind"
        private const val ARG_IS_EDIT = "is_edit"
        private const val ARG_TEXT = "text"
        private const val ARG_PRIORITY = "priority"
        private const val ARG_DUE_AT = "due_at"
        private const val ARG_QUANTITY = "quantity"
        private const val ARG_PRICE_CENTS = "price_cents"

        const val ARG_RESULT_TEXT = "r_text"
        const val ARG_RESULT_ITEM_ID = "r_item_id"
        const val ARG_RESULT_PRIORITY = "r_priority"
        const val ARG_RESULT_DUE_AT = "r_due_at"
        const val ARG_RESULT_QUANTITY = "r_quantity"
        const val ARG_RESULT_PRICE_CENTS = "r_price_cents"
        const val ARG_RESULT_IS_SHOPPING = "r_is_shopping"

        private const val ARG_ID = "item_id"

        fun newInstance(kind: CategoryKind, item: ItemEntity?): ItemEditDialog =
            ItemEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_KIND, kind.dbValue)
                    putBoolean(ARG_IS_EDIT, item != null)
                    putLong(ARG_ID, item?.id ?: 0L)
                    putString(ARG_TEXT, item?.text)
                    putInt(ARG_PRIORITY, item?.priority ?: Priority.NORMAL)
                    putLong(ARG_DUE_AT, item?.dueAt ?: NO_DUE)
                    putString(ARG_QUANTITY, item?.quantity)
                    putLong(ARG_PRICE_CENTS, item?.priceCents ?: NO_PRICE)
                }
            }
    }
}

object DisplayFormats {
    val dueDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
}