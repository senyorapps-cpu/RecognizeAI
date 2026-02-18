package com.example.recognizeai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class LanguageBottomSheet : BottomSheetDialogFragment() {

    var onLanguageSelected: ((LanguageItem) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_language_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = view.findViewById<LinearLayout>(R.id.languageListContainer)
        val currentCode = LocaleHelper.getCurrentLanguageCode()

        for (lang in LocaleHelper.supportedLanguages) {
            val row = layoutInflater.inflate(R.layout.item_language_row, container, false)

            row.findViewById<TextView>(R.id.tvFlag).text = lang.flag
            row.findViewById<TextView>(R.id.tvLanguageName).text = lang.nativeName

            val checkmark = row.findViewById<ImageView>(R.id.ivCheck)
            checkmark.visibility = if (lang.code == currentCode) View.VISIBLE else View.GONE

            row.setOnClickListener {
                onLanguageSelected?.invoke(lang)
                dismiss()
            }

            container.addView(row)
        }
    }

    companion object {
        const val TAG = "LanguageBottomSheet"
    }
}
