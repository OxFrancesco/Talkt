package org.buddytools.talkt

import android.app.Application
import com.clerk.api.Clerk

class TalktApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName() == packageName && BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            Clerk.initialize(this, BuildConfig.CLERK_PUBLISHABLE_KEY)
        }
    }
}
