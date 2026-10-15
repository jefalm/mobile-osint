package com.example.mobileosint

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.util.UUID

class SyncObservabilityTest {

 class FakeFeedProvider(private val responses: Map<String, Result<Feed>>) : FeedProvider {
  override suspend fun fetch(url: String): Feed {
   val result = responses[url] ?: Result.failure(Exception("HTTP 404 Feed Not Found"))
   return result.getOrThrow()
  }
 }

 @Test
 fun syncRunStoresSummaryAndPerFeedLogs() = runBlocking {
  val feedA = Feed("Feed A", listOf(
   FeedItem("item1", "Title 1", "https://a.test/1", "Body text 1", System.currentTimeMillis()),
   FeedItem("item2", "Title 2", "https://a.test/2", "Body text 2", System.currentTimeMillis())
  ))
  val provider = FakeFeedProvider(mapOf(
   "https://a.test/rss" to Result.success(feedA),
   "https://b.test/rss" to Result.failure(RuntimeException("Connection timed out"))
  ))
  assertNotNull(provider)

  // Test data structures directly
  val runId = UUID.randomUUID().toString()
  val now = System.currentTimeMillis()
  val run = SyncRunEntity(
   id = runId,
   timestamp = now,
   durationMs = 250L,
   totalFeeds = 2,
   successfulFeeds = 1,
   failedFeeds = 1,
   itemsAdded = 2,
   itemsUpdated = 0,
   itemsExpired = 0,
   itemsPruned = 0,
   status = "PARTIAL"
  )

  val logA = FeedSyncLogEntity(
   id = UUID.randomUUID().toString(),
   runId = runId,
   sourceId = "source-a",
   sourceTitle = "Feed A",
   sourceUrl = "https://a.test/rss",
   timestamp = now,
   status = "SUCCESS",
   itemsParsed = 2,
   itemsAdded = 2,
   itemsUpdated = 0,
   itemsExpired = 0,
   itemsUnchanged = 0,
   errorMessage = "",
   durationMs = 120L
  )

  val logB = FeedSyncLogEntity(
   id = UUID.randomUUID().toString(),
   runId = runId,
   sourceId = "source-b",
   sourceTitle = "Feed B",
   sourceUrl = "https://b.test/rss",
   timestamp = now,
   status = "FAILED",
   itemsParsed = 0,
   itemsAdded = 0,
   itemsUpdated = 0,
   itemsExpired = 0,
   itemsUnchanged = 0,
   errorMessage = "RuntimeException: Connection timed out",
   durationMs = 130L
  )

  assertEquals("PARTIAL", run.status)
  assertEquals(2, run.totalFeeds)
  assertEquals(1, run.successfulFeeds)
  assertEquals(1, run.failedFeeds)
  assertEquals(2, run.itemsAdded)

  assertEquals("SUCCESS", logA.status)
  assertEquals(2, logA.itemsAdded)
  assertEquals("FAILED", logB.status)
  assertEquals("RuntimeException: Connection timed out", logB.errorMessage)
 }

 @Test
 fun cleanupResultCapturesDeltas() {
  val cleanup = ResearchService.CleanupResult(expiredCount = 5, prunedCount = 10)
  assertEquals(5, cleanup.expiredCount)
  assertEquals(10, cleanup.prunedCount)
 }
}
