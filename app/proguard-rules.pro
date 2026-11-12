# Lunar calendar
-keep class com.nlf.calendar.** { *; }

# ThreeTenABP
-keep class org.threeten.bp.** { *; }
-dontwarn org.threeten.bp.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# 数据模型（Gson 反射依赖字段名，混淆后需保留原字段名）
-keep class com.calendardesk.util.CitySearch$CityEntry { *; }
-keep class com.calendardesk.util.WeatherFetcher$WeatherData { *; }
-keep class com.calendardesk.util.WeatherFetcher$ForecastDay { *; }
-keep class com.calendardesk.util.WeatherFetcher$CitySuggestion { *; }
-keep class com.calendardesk.util.HolidayFetcher$HolidayInfo { *; }
