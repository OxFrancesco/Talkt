package org.buddytools.talkt

import android.app.Application
import android.util.Log
import com.clerk.api.Clerk
import org.buddytools.talkt.capture.RecordingStore

class TalktApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName() == packageName) {
            if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) Clerk.initialize(this, BuildConfig.CLERK_PUBLISHABLE_KEY)
            java.io.File(applicationInfo.dataDir, "shared_prefs").listFiles()
                ?.filter { it.name.startsWith("drive-") && it.extension == "xml" }
                ?.forEach { file ->
                    val suffix = file.nameWithoutExtension.removePrefix("drive-")
                    androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag("drive-backup-$suffix")
                    deleteSharedPreferences(file.nameWithoutExtension)
                }
            // A `.part` file at process start means the last recording died with the process.
            val adopted = RecordingStore(filesDir).adoptOrphans()
            if (adopted.isNotEmpty()) Log.i("Talkt", "kept ${adopted.size} recording(s) from a previous process")
        }
    }
}
