package com.xfiles.android.feedback

import android.content.Context
import android.os.Build
import com.xfiles.android.deviceDescription
import com.xfiles.feedback.FeedbackEnvironment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Singleton

/** The non-personal details a report may carry. Read from the package and the OS only: never files or paths. */
@Module
@InstallIn(SingletonComponent::class)
internal object FeedbackEnvironmentModule {
    @Provides
    @Singleton
    fun feedbackEnvironment(@ApplicationContext context: Context): FeedbackEnvironment {
        val device = deviceDescription(context)
        return FeedbackEnvironment(
            appVersion = device.versionName,
            versionCode = device.versionCode,
            deviceModel = device.model,
            androidSdk = Build.VERSION.SDK_INT,
            flavor = if (context.packageName.endsWith(".dev")) "dev" else "prod",
            locale = Locale.getDefault().toLanguageTag(),
        )
    }
}
