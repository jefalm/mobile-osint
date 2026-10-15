package com.example.mobileosint

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceBulkToggleTest {
 @Test fun bulkFetchTogglePersistsAndDisabledFeedsAreSkippedBySync() = runBlocking {
  val context = ApplicationProvider.getApplicationContext<Context>()
  val databaseName = "bulk-toggle-${UUID.randomUUID()}.db"
  fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
   .allowMainThreadQueries().build()

  var database = openDatabase()
  try {
   var service = ResearchService(database, Settings(context))
   service.initialize()
   database.dao().addSource(SourceEntity("source-a", "https://127.0.0.1:1/a", "A", enabled=true, research=false))
   database.dao().addSource(SourceEntity("source-b", "https://127.0.0.1:1/b", "B", enabled=true, research=true))

   service.setAllFeedsEnabled(false)
   database.close()
   database = openDatabase()
   service = ResearchService(database, Settings(context))
   service.initialize()

   val disabledA = database.dao().source("source-a")!!
   val disabledB = database.dao().source("source-b")!!
   assertEquals(false, disabledA.enabled)
   assertEquals(false, disabledB.enabled)
   assertEquals(false, disabledA.research)
   assertEquals(true, disabledB.research)
   assertEquals(1, disabledA.revision)
   assertEquals(1, disabledB.revision)

   assertTrue(service.sync())
   assertEquals("NEW", database.dao().source("source-a")!!.status)
   assertEquals(0L, database.dao().source("source-a")!!.lastAttempt)
   assertEquals("NEW", database.dao().source("source-b")!!.status)
   assertEquals(0L, database.dao().source("source-b")!!.lastAttempt)

   service.setAllFeedsEnabled(true)
   val enabledA = database.dao().source("source-a")!!
   val enabledB = database.dao().source("source-b")!!
   assertEquals(true, enabledA.enabled)
   assertEquals(true, enabledB.enabled)
   assertEquals(false, enabledA.research)
   assertEquals(true, enabledB.research)
   assertEquals(2, enabledA.revision)
   assertEquals(2, enabledB.revision)

   val latestRun = database.dao().latestSyncRun()
   assertTrue(latestRun != null)
   assertEquals("SUCCESS", latestRun!!.status)
   assertEquals(0, latestRun.totalFeeds)
  } finally {
   database.close()
   context.deleteDatabase(databaseName)
  }
 }
}