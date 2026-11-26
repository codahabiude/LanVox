package com.example.wifip2pchat

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog for composing a 2–4 option interactive poll right inside any 1:1 or Group chat thread.
 */
object PollCreatorDialog {

    fun show(context: Context, onCreatePoll: (PollData) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_create_poll, null)
        val etQuestion = view.findViewById<EditText>(R.id.etPollQuestion)
        val etOpt1 = view.findViewById<EditText>(R.id.etPollOpt1)
        val etOpt2 = view.findViewById<EditText>(R.id.etPollOpt2)
        val etOpt3 = view.findViewById<EditText>(R.id.etPollOpt3)
        val etOpt4 = view.findViewById<EditText>(R.id.etPollOpt4)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.poll_dialog_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.poll_send_btn, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val question = etQuestion.text.toString().trim()
                val options = listOf(
                    etOpt1.text.toString(),
                    etOpt2.text.toString(),
                    etOpt3.text.toString(),
                    etOpt4.text.toString()
                ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

                if (question.isEmpty() || options.size < 2) {
                    Toast.makeText(context, R.string.poll_validation_error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val poll = PollData.create(question, options)
                dialog.dismiss()
                onCreatePoll(poll)
            }
        }
        dialog.show()
    }
}
