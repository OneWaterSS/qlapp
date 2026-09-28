package com.example.qlapp.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Weather(val city: String, val description: String, val temperature: String, val suggestion: String)

class WeatherApi {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    fun fetch(lat: Double, lon: Double): Weather {
        fun get(url: String): JSONObject = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("天气服务暂时不可用")
            JSONObject(r.body?.string().orEmpty())
        }
        val w = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto")
            .optJSONObject("current") ?: error("天气数据格式异常")
        val code = w.optInt("weather_code", -1)
        val desc = when (code) { 0 -> "晴"; 1, 2 -> "少云"; 3 -> "阴"; in 51..67, in 80..82 -> "有雨"; in 71..77, 85, 86 -> "有雪"; 95, 96, 99 -> "雷雨"; else -> "天气变化" }
        val temp = w.optDouble("temperature_2m").toString()
        val suggestion = when { desc.contains("雨") || desc.contains("雷") -> "今天有雨，记得带伞，适合在家一起看电影。"; desc.contains("雪") -> "有雪，出门注意保暖和路面湿滑。"; desc == "晴" -> "天气不错，适合一起出门走走。"; else -> "出门前留意天气变化，照顾好彼此。" }
        return Weather("当前位置", desc, temp, suggestion)
    }
}
