package io.github.codex07.iou.ui.settings

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UiPreferencesModule {
    @Provides @FileIo fun fileIo(): kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    @Provides @Singleton fun theme(@ApplicationContext context: Context): ThemePreferences {
        val preferences = context.getSharedPreferences("iou_ui_appearance", Context.MODE_PRIVATE)
        return ThemePreferences(object : ThemeStorage {
            override fun read() = preferences.getString("theme", null)
            override fun write(value: String) = preferences.edit().putString("theme", value).commit()
        })
    }
}
