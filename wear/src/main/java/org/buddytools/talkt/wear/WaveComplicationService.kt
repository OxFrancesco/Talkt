package org.buddytools.talkt.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class WaveComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        complication(request.complicationType, PendingIntent.getActivity(
            this, 2, Intent(this, WatchActivity::class.java)
                .putExtra(WatchActivity.EXTRA_TOGGLE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))

    override fun getPreviewData(type: ComplicationType): ComplicationData? = complication(type, null)

    private fun complication(type: ComplicationType, tap: PendingIntent?): ComplicationData? {
        val image = Icon.createWithResource(this, R.drawable.ic_talkt_wave)
        val description = PlainComplicationText.Builder("Record with Talkt").build()
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                MonochromaticImage.Builder(image).setAmbientImage(image).build(), description,
            ).setTapAction(tap).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(image, SmallImageType.ICON).setAmbientImage(image).build(), description,
            ).setTapAction(tap).build()
            else -> null
        }
    }
}
