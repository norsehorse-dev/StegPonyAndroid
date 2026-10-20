package com.stegpony.android.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** A file to create: suggested name and MIME type. */
data class NewDocument(val name: String, val mime: String)

/**
 * The system "save as" dialog with the MIME type chosen at launch time, unlike
 * ActivityResultContracts.CreateDocument, which fixes it when registered.
 */
class CreateTypedDocument : ActivityResultContract<NewDocument, Uri?>() {
    override fun createIntent(context: Context, input: NewDocument): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
