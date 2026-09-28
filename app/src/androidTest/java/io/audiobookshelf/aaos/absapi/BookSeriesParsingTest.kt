package io.audiobookshelf.aaos.absapi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BookSeriesParsingTest {
    @Test
    fun parsesMultipleSeriesAndPreservesSequenceText() {
        assertEquals(
            listOf(BookSeriesSummary("a", "Main", "1.5"), BookSeriesSummary("b", "Subseries", null)),
            parseBookSeries(JSONObject("""{"series":[{"id":"a","name":"Main","sequence":"1.5"},{"id":"b","name":"Subseries","sequence":null}]}""")),
        )
        assertEquals(emptyList<BookSeriesSummary>(), parseBookSeries(JSONObject("""{"series":[]}""")))
    }

    @Test
    fun rejectsMissingOrContradictorySeriesInsteadOfReturningAnEmptyCatalog() {
        listOf(
            """{}""",
            """{"series":null}""",
            """{"series":[{"id":"a","name":null}]}""",
            """{"series":[{"id":"","name":"Main"}]}""",
            """{"series":[{"id":"a","name":"Main","sequence":{}}]}""",
            """{"series":[{"id":"a","name":"Main","sequence":"1"},{"id":"a","name":"Main","sequence":"2"}]}""",
        ).forEach { json ->
            assertThrows(IOException::class.java) { parseBookSeries(JSONObject(json)) }
        }
    }
}
