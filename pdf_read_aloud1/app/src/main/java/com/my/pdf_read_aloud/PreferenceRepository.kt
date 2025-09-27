package com.my.pdf_read_aloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pdf_reader_preferences")

class PreferenceRepository(private val context: Context) {
    
    companion object {
        private val EXCLUDED_TEXT_KEY = stringPreferencesKey("excluded_text")
        private val LAST_DONATION_DIALOG_SHOWN = longPreferencesKey("last_donation_dialog_shown")
        //MyTodo : change this back to 30
        private const val DONATION_DIALOG_INTERVAL_DAYS = 30L//30L
    }
    
    val excludedTexts: Flow<List<String>> = context.dataStore.data.map { preferences ->
        val excludedTextString = preferences[EXCLUDED_TEXT_KEY] ?: ""
        if (excludedTextString.isEmpty()) {
            emptyList()
        } else {
            excludedTextString.split(",")
        }
    }
    
    val shouldShowDonationDialog: Flow<Boolean> = context.dataStore.data.map { preferences ->
        val lastShown = preferences[LAST_DONATION_DIALOG_SHOWN] ?: 0L
        val currentTime = System.currentTimeMillis()
        val daysSinceLastShown = TimeUnit.MILLISECONDS.toDays(currentTime - lastShown)
        
        lastShown == 0L || daysSinceLastShown >= DONATION_DIALOG_INTERVAL_DAYS
    }
    
    suspend fun addExcludedText(text: String) {
        context.dataStore.edit { preferences ->
            val currentList = preferences[EXCLUDED_TEXT_KEY]?.split(",") ?: emptyList()
            if (text.isNotBlank() && !currentList.contains(text)) {
                val newList = currentList.toMutableList().apply { add(text) }
                preferences[EXCLUDED_TEXT_KEY] = newList.joinToString(",")
            }
        }
    }
    
    suspend fun removeExcludedText(text: String) {
        context.dataStore.edit { preferences ->
            val currentList = preferences[EXCLUDED_TEXT_KEY]?.split(",") ?: emptyList()
            val newList = currentList.filter { it != text }
            preferences[EXCLUDED_TEXT_KEY] = newList.joinToString(",")
        }
    }
    
    suspend fun updateDonationDialogShownTime() {
        context.dataStore.edit { preferences ->
            preferences[LAST_DONATION_DIALOG_SHOWN] = System.currentTimeMillis()
        }
    }
} 