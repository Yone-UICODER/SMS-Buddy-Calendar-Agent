package com.example.calendarsms

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.QuotaExceededException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock



sealed class GeminiResult {

    data class Success(
        val text: String
    ) : GeminiResult()

    data class QuotaExceeded(
        val retryAfterMillis: Long
    ) : GeminiResult()

    data class Error(
        val message: String
    ) : GeminiResult()
}



class GeminiService {

    companion object {

        private const val TAG = "GeminiService"


        private val quotaMutex = Mutex()


        @Volatile
        private var quotaUnavailableUntil: Long = 0L
    }


    private val model =
        Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            "gemini-3.8-flash"
        )


    suspend fun askGemini(
        prompt: String
    ): GeminiResult {



        val currentTime =
            System.currentTimeMillis()

        if (currentTime < quotaUnavailableUntil) {

            val remaining =
                quotaUnavailableUntil - currentTime

            Log.d(
                TAG,
                "Gemini quota still unavailable. " +
                        "Remaining: " +
                        formatDurationForLog(remaining)
            )

            return GeminiResult.QuotaExceeded(
                retryAfterMillis = remaining
            )
        }



        return try {

            Log.d(
                TAG,
                "Sending request to Gemini..."
            )

            val response =
                model.generateContent(prompt)




            val text =
                response.text
                    ?.trim()
                    .orEmpty()


            Log.d(
                TAG,
                "Gemini response received: $text"
            )


            GeminiResult.Success(
                text = text
            )

        } catch (e: QuotaExceededException) {


            Log.e(
                TAG,
                "quota exceeded.",
                e
            )




            val retryAfterMillis =
                extractRetryDurationMillis(
                    e.message.orEmpty()
                )

            quotaMutex.withLock {

                quotaUnavailableUntil =
                    System.currentTimeMillis() +
                            retryAfterMillis
            }


            Log.d(
                TAG,
                "AI unavailable until: " +
                        quotaUnavailableUntil
            )


            GeminiResult.QuotaExceeded(
                retryAfterMillis =
                    retryAfterMillis
            )

        } catch (e: Exception) {


            Log.e(
                TAG,
                "Gemini request failed.",
                e
            )


            GeminiResult.Error(
                message =
                    e.message.orEmpty()
            )
        }
    }




    private fun extractRetryDurationMillis(
        errorMessage: String
    ): Long {

        val regex =
            Regex(
                """retry in\s+(?:(\d+)h)?(?:(\d+)m)?(?:(\d+(?:\.\d+)?)s)?""",
                RegexOption.IGNORE_CASE
            )


        val match =
            regex.find(errorMessage)


        if (match != null) {

            val hours =
                match.groupValues[1]
                    .toLongOrNull()
                    ?: 0L

            val minutes =
                match.groupValues[2]
                    .toLongOrNull()
                    ?: 0L

            val seconds =
                match.groupValues[3]
                    .toDoubleOrNull()
                    ?: 0.0


            val totalMillis =
                (hours * 60L * 60L * 1000L) +
                        (minutes * 60L * 1000L) +
                        (seconds * 1000.0).toLong()


            if (totalMillis > 0L) {

                return totalMillis
            }
        }




        return 60L * 60L * 1000L
    }




    private fun formatDurationForLog(
        millis: Long
    ): String {

        val totalSeconds =
            millis / 1000L


        val hours =
            totalSeconds / 3600L


        val minutes =
            (totalSeconds % 3600L) / 60L


        val seconds =
            totalSeconds % 60L


        return "${hours}h ${minutes}m ${seconds}s"
    }
}