package com.example.mobileosint

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddSourceDuplicateTest {
 @Test fun addingSameFeedTwiceReportsDuplicateWithoutRefetching() = runBlocking {
  val context = ApplicationProvider.getApplicationContext<Context>()
  val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
   .allowMainThreadQueries().build()
  var fetchCount = 0
  val feedProvider = object : FeedProvider {
   override suspend fun fetch(url: String): Feed {
    fetchCount++
    return Feed("Example", emptyList())
   }
  }

  try {
   val service = ResearchService(database, Settings(context), feedProvider)
   service.initialize()
   val url = "https://example.com/feed.xml"
   service.addSource(url)

   val exception = try {
    service.addSource(url)
    fail("Expected duplicate feed to be rejected")
    return@runBlocking
   } catch (error: IllegalArgumentException) {
    error
   }

   assertEquals("Feed already exists", exception.message)
   assertEquals(1, fetchCount)
   assertEquals(1, database.dao().sourceCount())
  } finally {
   database.close()
  }
 }
}