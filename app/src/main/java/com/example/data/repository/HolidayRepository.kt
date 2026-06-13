package com.example.data.repository

import android.util.Log
import com.example.data.local.HolidayDao
import com.example.data.model.Holiday
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

sealed class SyncResult {
    data class Success(val source: String, val holidaysCount: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

class HolidayRepository(private val holidayDao: HolidayDao) {

    val allHolidays: Flow<List<Holiday>> = holidayDao.getAllHolidays()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getHolidaysForYear(year: Int): List<Holiday> {
        return holidayDao.getHolidaysByYear(year)
    }

    suspend fun isHoliday(dateString: String): Boolean {
        return holidayDao.isHoliday(dateString)
    }

    suspend fun getHolidayName(dateString: String): String? {
        return holidayDao.getHolidayByDate(dateString)?.name
    }

    suspend fun insertHoliday(holiday: Holiday) {
        holidayDao.insertHoliday(holiday)
    }

    suspend fun deleteHoliday(holiday: Holiday) {
        holidayDao.deleteHoliday(holiday)
    }

    suspend fun deleteHolidaysByYear(year: Int) {
        holidayDao.deleteHolidaysByYear(year)
    }

    suspend fun syncHolidays(
        year: Int,
        govtServiceKey: String?,
        geminiApiKey: String?
    ): SyncResult = withContext(Dispatchers.IO) {
        // Step 1: Try Government API if service key is provided
        if (!govtServiceKey.isNullOrBlank()) {
            val govResult = syncWithGovernmentApi(year, govtServiceKey)
            if (govResult is SyncResult.Success) {
                return@withContext govResult
            } else if (govResult is SyncResult.Error) {
                // FALLBACK as requested: if key is invalid or triggers error, fall back to offline formula/Gemini, and show it in the success source text!
                Log.w("HolidayRepository", "Government API key was invalid or triggered error: ${govResult.message}. Proceeding with offline calculations / AI sync fallback.")
                
                // Try Gemini API first if API key is provided
                if (!geminiApiKey.isNullOrBlank()) {
                    val geminiResult = syncWithGeminiApi(year, geminiApiKey)
                    if (geminiResult is SyncResult.Success) {
                        return@withContext SyncResult.Success(
                            "공공데이터 인증키 오류로 스마트 AI 동기화 성공",
                            geminiResult.holidaysCount
                        )
                    }
                }
                
                // Fallback to high-fidelity offline solver
                try {
                    val localHolidays = calculateLocalHolidays(year)
                    holidayDao.deleteHolidaysByYear(year)
                    holidayDao.insertHolidays(localHolidays)
                    Log.d("HolidayRepository", "Offline solver fallback success after government API key error")
                    return@withContext SyncResult.Success(
                        "공공데이터 인증키 오류로 로컬 공식 오프라인 계산식 수집 성공",
                        localHolidays.size
                    )
                } catch (e: Exception) {
                    return@withContext SyncResult.Error("공공데이터 오류 및 로컬 모델링 생성 실패: ${e.message}")
                }
            }
        }

        // Step 2: Try Gemini API if API key is provided
        if (!geminiApiKey.isNullOrBlank()) {
            val geminiResult = syncWithGeminiApi(year, geminiApiKey)
            if (geminiResult is SyncResult.Success) {
                return@withContext geminiResult
            }
        }

        // Step 3: Fallback to high-fidelity offline solver
        try {
            val localHolidays = calculateLocalHolidays(year)
            holidayDao.deleteHolidaysByYear(year)
            holidayDao.insertHolidays(localHolidays)
            Log.d("HolidayRepository", "Local Offline Fallback: Synchronized ${localHolidays.size} holidays")
            return@withContext SyncResult.Success("오프라인 로컬 알고리즘", localHolidays.size)
        } catch (e: Exception) {
            return@withContext SyncResult.Error("인터넷 연동 및 로컬 모델링 생성 실패: ${e.message}")
        }
    }

    // --- Government OpenAPI (`apis.data.go.kr`) Implementation ---
    private suspend fun syncWithGovernmentApi(year: Int, serviceKey: String): SyncResult {
        val url = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getHoliDeInfo" +
                "?solYear=$year" +
                "&numOfRows=100" +
                "&_type=json" +
                "&serviceKey=$serviceKey"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return SyncResult.Error("공공데이터 API 서버 오류: ${response.code}")
                }
                val bodyStr = response.body?.string() ?: return SyncResult.Error("응답 내용 없음")
                
                // If response is XML error message, parse and extract errMsg/returnAuthMsg
                if (bodyStr.trim().startsWith("<")) {
                    val authMsg = extractXmlTag(bodyStr, "returnAuthMsg")
                    val errMsg = extractXmlTag(bodyStr, "errMsg")
                    if (!authMsg.isNullOrBlank()) {
                        return SyncResult.Error("$authMsg ($errMsg)")
                    }
                    val code = extractXmlTag(bodyStr, "returnReasonCode")
                    if (!code.isNullOrBlank()) {
                        return SyncResult.Error("오류코드 $code ($errMsg)")
                    }
                    return SyncResult.Error(errMsg ?: "인증 실패 또는 잘못된 키 입니다.")
                }

                val parsedHolidays = mutableListOf<Holiday>()
                val root = JSONObject(bodyStr)
                val responseObj = root.optJSONObject("response") ?: return SyncResult.Error("올바르지 않은 응답 패킷 구조")
                val bodyObj = responseObj.optJSONObject("body") ?: return SyncResult.Error("바디 구조 유실")
                val itemsObj = bodyObj.optJSONObject("items") ?: return SyncResult.Error("공휴일 목록 데이터가 존재하지 않습니다.")
                
                val itemOpt = itemsObj.opt("item") ?: return SyncResult.Error("공휴일 세부 항목이 존재하지 않습니다.")
                
                if (itemOpt is JSONArray) {
                    for (i in 0 until itemOpt.length()) {
                        val obj = itemOpt.getJSONObject(i)
                        parseGovHoliday(obj, year)?.let { parsedHolidays.add(it) }
                    }
                } else if (itemOpt is JSONObject) {
                    parseGovHoliday(itemOpt, year)?.let { parsedHolidays.add(it) }
                }

                if (parsedHolidays.isNotEmpty()) {
                    holidayDao.deleteHolidaysByYear(year)
                    holidayDao.insertHolidays(parsedHolidays)
                    SyncResult.Success("대한민국 정부 공공데이터 API", parsedHolidays.size)
                } else {
                    SyncResult.Error("조회된 공휴일이 없습니다.")
                }
            }
        } catch (e: Exception) {
            SyncResult.Error("공공데이터 통신 중 예외 발생: ${e.message}")
        }
    }

    private fun extractXmlTag(xml: String, tagName: String): String? {
        val startTag = "<$tagName>"
        val endTag = "</$tagName>"
        if (xml.contains(startTag) && xml.contains(endTag)) {
            val start = xml.indexOf(startTag) + startTag.length
            val end = xml.indexOf(endTag, start)
            if (end > start) {
                return xml.substring(start, end).trim()
            }
        }
        return null
    }

    private fun parseGovHoliday(obj: JSONObject, year: Int): Holiday? {
        val locdate = obj.optLong("locdate", 0) // format: YYYYMMDD
        var dateName = obj.optString("dateName", "")
        val isHolidayStr = obj.optString("isHoliday", "N")
        
        if (locdate == 0L || isHolidayStr != "Y") return null
        
        val dateStr = locdate.toString()
        if (dateStr.length == 8) {
            val formattedDate = "${dateStr.substring(0, 4)}-${dateStr.substring(4, 6)}-${dateStr.substring(6, 8)}"
            val isAlternative = dateName.contains("대체") || dateName.contains("대체공휴일")
            if (dateStr.endsWith("0501") || dateName == "근로자의 날" || dateName.contains("근로자의 날")) {
                dateName = "노동절"
            }
            return Holiday(
                date = formattedDate,
                name = dateName,
                isAlternative = isAlternative,
                year = year
            )
        }
        return null
    }

    // --- Gemini Smart Sync Implementation ---
    private suspend fun syncWithGeminiApi(year: Int, apiKey: String): SyncResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        
        val prompt = """
            Provide a complete and accurate list of all South Korean official public holidays (공휴일) and alternative national holidays (대체공휴일) for the year $year in JSON format.
            Include:
            1. Solar holidays (New Year's Day, March 1st Movement, Labor Day May 1st if applicable, Children's Day May 5th, Memorial Day June 6th, Liberation Day August 15th, National Foundation Day October 3rd, Hangeul Day October 9th, Christmas December 25th).
            2. Lunar holidays (Lunar New Year Day and its adjacent days, Chuseok and its adjacent days, Buddha's Birthday).
            3. All corresponding official substitute/alternative holidays (대체공휴일) that apply when key holidays overlap with weekend days as defined by the South Korean presidential decree on holidays.
            
            Return strictly a JSON array without markdown formatting syntax. Each object in the array must contain:
            - "date": String in "YYYY-MM-DD" format
            - "name": String denoting Korean Name (e.g. "설날", "대체공휴일", "광복절")
            - "isAlternative": Boolean indicating if it is an alternative holiday
            
            Example Format:
            [
              {"date": "$year-03-01", "name": "3ㆍ1절", "isAlternative": false},
              {"date": "$year-05-05", "name": "어린이날", "isAlternative": false},
              {"date": "$year-05-06", "name": "대체공휴일", "isAlternative": true}
            ]
        """.trimIndent()

        val requestObj = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val requestBody = requestObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return SyncResult.Error("Gemini API 서버 오류: ${response.code}")
                }
                val bodyStr = response.body?.string() ?: return SyncResult.Error("Gemini 응답 내용 유실")
                
                val responseJson = JSONObject(bodyStr)
                val candidates = responseJson.optJSONArray("candidates") ?: return SyncResult.Error("올바르지 않은 Gemini 응답")
                val firstCandidate = candidates.optJSONObject(0) ?: return SyncResult.Error("Gemini 후보 응답 없음")
                val content = firstCandidate.optJSONObject("content") ?: return SyncResult.Error("컨텐츠 유실")
                val parts = content.optJSONArray("parts") ?: return SyncResult.Error("파츠 유실")
                val text = parts.optJSONObject(0)?.optString("text") ?: return SyncResult.Error("텍스트 데이터 없음")

                // Remove markdown codeblock qualifiers if present
                var cleanJson = text.trim()
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.substringAfter("[").substringBeforeLast("]")
                    cleanJson = "[$cleanJson]"
                }

                val holidayList = mutableListOf<Holiday>()
                val jsonArray = JSONArray(cleanJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val date = obj.getString("date")
                    var name = obj.getString("name")
                    val isAlternative = obj.optBoolean("isAlternative", false)
                    
                    if (date.endsWith("-05-01") || name == "근로자의 날" || name.contains("근로자의 날")) {
                        name = "노동절"
                    }
                    
                    holidayList.add(
                        Holiday(
                            date = date,
                            name = name,
                            isAlternative = isAlternative,
                            year = year
                        )
                    )
                }

                if (holidayList.isNotEmpty()) {
                    holidayDao.deleteHolidaysByYear(year)
                    holidayDao.insertHolidays(holidayList)
                    SyncResult.Success("Gemini 스마트 AI 동기화", holidayList.size)
                } else {
                    SyncResult.Error("파싱된 동기화 공휴일 목록이 비어있습니다.")
                }
            }
        } catch (e: Exception) {
            SyncResult.Error("Gemini 스마트 동기화 중 예외: ${e.message}")
        }
    }

    // --- Offline Calculator (High Fidelity Database Lookup + Rule Engine) ---
    private fun calculateLocalHolidays(year: Int): List<Holiday> {
        val holidays = mutableListOf<Holiday>()
        
        // Solar-fixed holidays
        holidays.add(Holiday("$year-01-01", "신정", false, year))
        holidays.add(Holiday("$year-03-01", "3ㆍ1절", false, year))
        holidays.add(Holiday("$year-05-01", "노동절", false, year))
        holidays.add(Holiday("$year-05-05", "어린이날", false, year))
        holidays.add(Holiday("$year-06-06", "현충일", false, year))
        if (year >= 2026) {
            holidays.add(Holiday("$year-07-17", "제헌절", false, year))
        }
        holidays.add(Holiday("$year-08-15", "광복절", false, year))
        holidays.add(Holiday("$year-10-03", "개천절", false, year))
        holidays.add(Holiday("$year-10-09", "한글날", false, year))
        holidays.add(Holiday("$year-12-25", "성탄절", false, year))

        // Lunar calendar lookups for Korea (2024–2030)
        when (year) {
            2024 -> {
                holidays.add(Holiday("2024-02-09", "설날 전날", false, year))
                holidays.add(Holiday("2024-02-10", "설날", false, year))
                holidays.add(Holiday("2024-02-11", "설날 다음날", false, year))
                holidays.add(Holiday("2024-05-15", "부처님오신날", false, year))
                holidays.add(Holiday("2024-09-16", "추석 전날", false, year))
                holidays.add(Holiday("2024-09-17", "추석", false, year))
                holidays.add(Holiday("2024-09-18", "추석 다음날", false, year))
            }
            2025 -> {
                holidays.add(Holiday("2025-01-28", "설날 전날", false, year))
                holidays.add(Holiday("2025-01-29", "설날", false, year))
                holidays.add(Holiday("2025-01-30", "설날 다음날", false, year))
                holidays.add(Holiday("2025-05-05", "부처님오신날", false, year)) // Buddha's bday and Children's day overlap
                holidays.add(Holiday("2025-10-05", "추석 전날", false, year))
                holidays.add(Holiday("2025-10-06", "추석", false, year))
                holidays.add(Holiday("2025-10-07", "추석 다음날", false, year))
            }
            2026 -> {
                holidays.add(Holiday("2026-02-16", "설날 전날", false, year))
                holidays.add(Holiday("2026-02-17", "설날", false, year))
                holidays.add(Holiday("2026-02-18", "설날 다음날", false, year))
                holidays.add(Holiday("2026-05-24", "부처님오신날", false, year))
                holidays.add(Holiday("2026-09-24", "추석 전날", false, year))
                holidays.add(Holiday("2026-09-25", "추석", false, year))
                holidays.add(Holiday("2026-09-26", "추석 다음날", false, year))
            }
            2027 -> {
                holidays.add(Holiday("2027-02-06", "설날 전날", false, year))
                holidays.add(Holiday("2027-02-07", "설날", false, year))
                holidays.add(Holiday("2027-02-08", "설날 다음날", false, year))
                holidays.add(Holiday("2027-05-13", "부처님오신날", false, year))
                holidays.add(Holiday("2027-09-14", "추석 전날", false, year))
                holidays.add(Holiday("2027-09-15", "추석", false, year))
                holidays.add(Holiday("2027-09-16", "추석 다음날", false, year))
            }
            2028 -> {
                holidays.add(Holiday("2028-01-26", "설날 전날", false, year))
                holidays.add(Holiday("2028-01-27", "설날", false, year))
                holidays.add(Holiday("2028-01-28", "설날 다음날", false, year))
                holidays.add(Holiday("2028-05-02", "부처님오신날", false, year))
                holidays.add(Holiday("2028-10-02", "추석 전날", false, year))
                holidays.add(Holiday("2028-10-03", "추석", false, year)) // Overlaps with Foundation Day
                holidays.add(Holiday("2028-10-04", "추석 다음날", false, year))
            }
            2029 -> {
                holidays.add(Holiday("2029-02-12", "설날 전날", false, year))
                holidays.add(Holiday("2029-02-13", "설날", false, year))
                holidays.add(Holiday("2029-02-14", "설날 다음날", false, year))
                holidays.add(Holiday("2029-05-20", "부처님오신날", false, year))
                holidays.add(Holiday("2029-09-21", "추석 전날", false, year))
                holidays.add(Holiday("2029-09-22", "추석", false, year))
                holidays.add(Holiday("2029-09-23", "추석 다음날", false, year))
            }
            2030 -> {
                holidays.add(Holiday("2030-02-02", "설날 전날", false, year))
                holidays.add(Holiday("2030-02-03", "설날", false, year))
                holidays.add(Holiday("2030-02-04", "설날 다음날", false, year))
                holidays.add(Holiday("2030-05-09", "부처님오신날", false, year))
                holidays.add(Holiday("2030-09-11", "추석 전날", false, year))
                holidays.add(Holiday("2030-09-12", "추석", false, year))
                holidays.add(Holiday("2030-09-13", "추석 다음날", false, year))
            }
            else -> {
                // If it goes beyond 2030, approximate using solar list
            }
        }

        // Apply South Korea Alternative Holiday rules:
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
        val alternativeHolidays = mutableListOf<Holiday>()

        // 1. Double check overlapping solar holidays or major lunar events
        val datesMap = holidays.associateBy { it.date }.toMutableMap()

        // Rules check loop
        for (h in holidays) {
            try {
                val date = sdf.parse(h.date) ?: continue
                val cal = Calendar.getInstance().apply { time = date }
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

                // Common Rule: March 1st, Children's Day, Buddha's Birthday, Liberation, Foundation, Hangeul, Christmas,
                // if they fall on Saturday (7) or Sunday (1)
                val isEligibleCommon = h.name in listOf("3ㆍ1절", "광복절", "개천절", "한글날", "어린이날", "부처님오신날", "성탄절", "제헌절")
                
                // Specific Rule for Lunar New Year or Chuseok:
                // overlapping with Sunday (1)
                val isLunarMain = h.name in listOf("설날", "설날 전날", "설날 다음날", "추석", "추석 전날", "추석 다음날")

                if (isEligibleCommon && (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)) {
                    // Find next non-holiday weekday
                    val altDateStr = findNextAvailableWeekday(h.date, datesMap, sdf)
                    alternativeHolidays.add(Holiday(altDateStr, "${h.name} 대체공휴일", true, year))
                    datesMap[altDateStr] = Holiday(altDateStr, "${h.name} 대체공휴일", true, year)
                } else if (isLunarMain && dayOfWeek == Calendar.SUNDAY) {
                    val altDateStr = findNextAvailableWeekday(h.date, datesMap, sdf)
                    alternativeHolidays.add(Holiday(altDateStr, "${h.name} 대체공휴일", true, year))
                    datesMap[altDateStr] = Holiday(altDateStr, "${h.name} 대체공휴일", true, year)
                }
            } catch (e: Exception) {
                // Parse Exception
            }
        }

        // Specific overlapping edge case resolving for May 5th 2025 (Buddha Birthday and Children day both on May 5th)
        if (year == 2025) {
            // Monday May 5th overlap -> alternative holiday on Tuesday May 6th
            alternativeHolidays.add(Holiday("2025-05-06", "대체공휴일", true, 2025))
        }

        holidays.addAll(alternativeHolidays)
        // Sort
        holidays.sortBy { it.date }
        return holidays.distinctBy { it.date }
    }

    private fun findNextAvailableWeekday(
        baseDateStr: String,
        holidaysMap: Map<String, Holiday>,
        sdf: SimpleDateFormat
    ): String {
        val cal = Calendar.getInstance().apply {
            time = sdf.parse(baseDateStr)!!
        }
        
        while (true) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val currentStr = sdf.format(cal.time)
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            
            // Should not be weekend, and not already in holidays mapping
            val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
            val isAlreadyHoliday = holidaysMap.containsKey(currentStr)
            
            if (!isWeekend && !isAlreadyHoliday) {
                return currentStr
            }
        }
    }
}
