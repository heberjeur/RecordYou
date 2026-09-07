package com.bnyro.recorder.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

data class LanguageOption(
    val code: String,
    val name: String,
    val icon: String
)

object LanguageHelper {
    val languages = listOf(
        LanguageOption("", "System", "🌐"),
        LanguageOption("en", "English", "🇬🇧"),
        LanguageOption("ar", "العربية", "🇸🇦"),
        LanguageOption("az", "Azərbaycan", "🇦🇿"),
        LanguageOption("be", "Беларуская", "🇧🇾"),
        LanguageOption("bn", "বাংলা", "🇧🇩"),
        LanguageOption("ca", "Català", "🇦🇩"),
        LanguageOption("cs", "Čeština", "🇨🇿"),
        LanguageOption("da", "Dansk", "🇩🇰"),
        LanguageOption("de", "Deutsch", "🇩🇪"),
        LanguageOption("es", "Español", "🇪🇸"),
        LanguageOption("et", "Eesti", "🇪🇪"),
        LanguageOption("fa", "فارسی", "🇮🇷"),
        LanguageOption("fi", "Suomi", "🇫🇮"),
        LanguageOption("fil", "Filipino", "🇵🇭"),
        LanguageOption("fr", "Français", "🇫🇷"),
        LanguageOption("hi", "हिन्दी", "🇮🇳"),
        LanguageOption("ia", "Interlingua", "🌐"),
        LanguageOption("in", "Bahasa Indonesia", "🇮🇩"),
        LanguageOption("it", "Italiano", "🇮🇹"),
        LanguageOption("iw", "עברית", "🇮🇱"),
        LanguageOption("ja", "日本語", "🇯🇵"),
        LanguageOption("ms", "Bahasa Melayu", "🇲🇾"),
        LanguageOption("my", "မြန်မာ", "🇲🇲"),
        LanguageOption("nb-NO", "Norsk Bokmål", "🇳🇴"),
        LanguageOption("nn", "Norsk Nynorsk", "🇳🇴"),
        LanguageOption("or", "ଓଡ଼ିଆ", "🇮🇳"),
        LanguageOption("pl", "Polski", "🇵🇱"),
        LanguageOption("pt", "Português", "🇵🇹"),
        LanguageOption("pt-BR", "Português (Brasil)", "🇧🇷"),
        LanguageOption("ro", "Română", "🇷🇴"),
        LanguageOption("ru", "Русский", "🇷🇺"),
        LanguageOption("ryu", "沖縄語", "🇯🇵"),
        LanguageOption("sat", "ᱥᱟᱱᱛᱟᱲᱤ", "🇮🇳"),
        LanguageOption("sr", "Српски", "🇷🇸"),
        LanguageOption("sv", "Svenska", "🇸🇪"),
        LanguageOption("ta", "தமிழ்", "🇮🇳"),
        LanguageOption("tr", "Türkçe", "🇹🇷"),
        LanguageOption("uk", "Українська", "🇺🇦"),
        LanguageOption("zh-CN", "中文 (简体)", "🇨🇳"),
        LanguageOption("zh-TW", "中文 (繁體)", "🇹🇼")
    )

    fun wrapContext(base: Context): Context {
        Preferences.init(base)
        val lang = Preferences.prefs.getString(Preferences.languageKey, "") ?: ""
        if (lang.isEmpty()) return base

        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun setLanguage(context: Context, langCode: String) {
        Preferences.edit { putString(Preferences.languageKey, langCode) }
        if (langCode.isNotEmpty()) {
            val locale = Locale.forLanguageTag(langCode)
            Locale.setDefault(locale)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val localeList = if (langCode.isNotEmpty()) {
                LocaleList.forLanguageTags(langCode)
            } else {
                LocaleList.getEmptyLocaleList()
            }
            localeManager?.applicationLocales = localeList
        }
    }
}
