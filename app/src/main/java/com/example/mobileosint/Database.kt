package com.example.mobileosint
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
@Entity(tableName="sources",indices=[Index(value=["url"],unique=true)])
data class SourceEntity(@PrimaryKey val id:String,val url:String,val title:String,val enabled:Boolean=true,val research:Boolean=true,val revision:Int=0,val addedAt:Long=System.currentTimeMillis(),val lastAttempt:Long=0,val lastSuccess:Long=0,val status:String="NEW",val failures:Int=0,val tags:String="")
@Entity(tableName="articles",foreignKeys=[ForeignKey(entity=SourceEntity::class,parentColumns=["id"],childColumns=["sourceId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sourceId"),Index("effectiveAt")])
data class ArticleEntity(@PrimaryKey val id:String,val sourceId:String,val title:String,val url:String,val body:String,val publishedAt:Long?,val firstSeenAt:Long,val effectiveAt:Long,val hash:String,val contentType:String="UNKNOWN",val language:String="und")
@Entity(tableName="chunks",foreignKeys=[ForeignKey(entity=ArticleEntity::class,parentColumns=["id"],childColumns=["articleId"],onDelete=ForeignKey.CASCADE)],indices=[Index("articleId")])
data class ChunkEntity(@PrimaryKey val id:String,val articleId:String,val text:String,val embedding:ByteArray?=null,val embeddingModel:String="")
@Entity(tableName="seen",foreignKeys=[ForeignKey(entity=SourceEntity::class,parentColumns=["id"],childColumns=["sourceId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sourceId")])
data class SeenEntity(@PrimaryKey val id:String,val sourceId:String,val firstSeenAt:Long)
@Entity(tableName="reports")
data class ReportEntity(@PrimaryKey val id:String,val question:String,val text:String="",val status:String="RUNNING",val requestedModel:String,val actualModel:String="",val createdAt:Long=System.currentTimeMillis(),val error:String="",val promptVersion:String="osint-v1")
@Entity(tableName="evidence",foreignKeys=[ForeignKey(entity=ReportEntity::class,parentColumns=["id"],childColumns=["reportId"],onDelete=ForeignKey.CASCADE)],indices=[Index("reportId")])
data class EvidenceEntity(@PrimaryKey val id:String,val reportId:String,val citation:Int,val title:String,val url:String,val source:String,val text:String,val publishedAt:Long?,val retrievedAt:Long,val contentHash:String,val contentType:String="UNKNOWN",val firstSeenAt:Long?=null)
data class Candidate(val id:String,val articleId:String,val text:String,val embedding:ByteArray?,val embeddingModel:String,val title:String,val url:String,val source:String,val sourceId:String,val publishedAt:Long?,val hash:String,val contentType:String="UNKNOWN",val effectiveAt:Long=0,val firstSeenAt:Long=0,val language:String="und",val sourceUrl:String="",val tags:String="")
data class Coverage(val articleCount:Int,val sourceCount:Int,val newestAt:Long?,val lastRefresh:Long?)
data class TrendArticle(val id:String,val title:String,val url:String,val body:String,val publishedAt:Long?,val firstSeenAt:Long,val effectiveAt:Long,val source:String,val sourceId:String,val sourceUrl:String,val language:String="und")
data class TrendFeedHealth(val sourceCount:Int,val staleCount:Int,val lastRefresh:Long?)
data class TrendSourceRefresh(val id:String,val lastSuccess:Long)
@Dao interface OsintDao {
 @Query("SELECT COUNT(*) FROM sources") suspend fun sourceCount():Int
 @Query("SELECT url FROM sources") suspend fun sourceUrls():List<String>
 @Query("SELECT COUNT(*) FROM sources WHERE url=:url") suspend fun hasSourceUrl(url:String):Int
 @Query("SELECT * FROM seen WHERE id=:id") suspend fun seen(id:String):SeenEntity?
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun remember(s:SeenEntity)
 @Query("DELETE FROM seen WHERE id NOT IN (SELECT id FROM seen ORDER BY firstSeenAt DESC LIMIT 50000)") suspend fun capSeen()

 @Query("SELECT * FROM sources ORDER BY addedAt") fun sources():Flow<List<SourceEntity>>
 @Query("SELECT * FROM sources WHERE enabled=1") suspend fun active():List<SourceEntity>
 @Query("SELECT * FROM sources WHERE id=:id") suspend fun source(id:String):SourceEntity?
 @Insert suspend fun addSource(s:SourceEntity)
 @Update suspend fun updateSource(s:SourceEntity)
 @Query("UPDATE sources SET enabled=:enabled, revision=revision+1 WHERE enabled!=:enabled") suspend fun setAllFetchEnabled(enabled:Boolean):Int
 @Query("DELETE FROM sources WHERE id=:id") suspend fun deleteSource(id:String)
 @Query("SELECT * FROM articles WHERE id=:id") suspend fun article(id:String):ArticleEntity?
 @Query("SELECT * FROM articles WHERE language='und' LIMIT 5000") suspend fun unclassifiedArticles():List<ArticleEntity>
 @Query("UPDATE articles SET language=:language WHERE id=:id AND language='und'") suspend fun setArticleLanguage(id:String,language:String)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putArticle(a:ArticleEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putChunks(c:List<ChunkEntity>)
 @Query("DELETE FROM chunks WHERE articleId=:articleId") suspend fun deleteChunks(articleId:String)
 @Query("DELETE FROM articles WHERE effectiveAt < :cutoff") suspend fun expire(cutoff:Long)
 @Query("DELETE FROM articles WHERE id NOT IN (SELECT id FROM articles ORDER BY effectiveAt DESC,id LIMIT :cap)") suspend fun cap(cap:Int)
 @Query("SELECT COUNT(*) FROM articles") fun count():Flow<Int>
 @Query("SELECT c.id,c.articleId,c.text,c.embedding,c.embeddingModel,a.title,a.url,s.title AS source,s.id AS sourceId,a.publishedAt,a.hash,a.contentType,a.effectiveAt,a.firstSeenAt,a.language,s.url AS sourceUrl,s.tags FROM chunks c JOIN articles a ON a.id=c.articleId JOIN sources s ON s.id=a.sourceId WHERE s.research=1 AND a.effectiveAt BETWEEN :fromAt AND :toAt AND (:allSources=1 OR s.id IN (:sourceIds)) AND a.language IN (:languages) AND (:topicPattern='' OR a.title LIKE :topicPattern ESCAPE '!' OR a.body LIKE :topicPattern ESCAPE '!' OR a.title LIKE :alternatePattern ESCAPE '!' OR a.body LIKE :alternatePattern ESCAPE '!') ORDER BY a.effectiveAt DESC,c.id LIMIT 4000") suspend fun candidates(fromAt:Long,toAt:Long,allSources:Boolean,sourceIds:List<String>,languages:List<String>,topicPattern:String,alternatePattern:String):List<Candidate>
 @Query("SELECT COUNT(DISTINCT a.id) AS articleCount,COUNT(DISTINCT CASE WHEN a.id IS NOT NULL THEN s.id END) AS sourceCount,MAX(a.effectiveAt) AS newestAt,MAX(s.lastSuccess) AS lastRefresh FROM sources s LEFT JOIN articles a ON a.sourceId=s.id AND a.effectiveAt BETWEEN :fromAt AND :toAt AND a.language IN (:languages) AND (:topicPattern='' OR a.title LIKE :topicPattern ESCAPE '!' OR a.body LIKE :topicPattern ESCAPE '!' OR a.title LIKE :alternatePattern ESCAPE '!' OR a.body LIKE :alternatePattern ESCAPE '!') WHERE s.research=1 AND (:allSources=1 OR s.id IN (:sourceIds))") suspend fun coverage(fromAt:Long,toAt:Long,allSources:Boolean,sourceIds:List<String>,languages:List<String>,topicPattern:String,alternatePattern:String):Coverage
 @Query("SELECT a.id,a.title,a.url,a.body,a.publishedAt,a.firstSeenAt,a.effectiveAt,a.language,s.title AS source,s.id AS sourceId,s.url AS sourceUrl FROM articles a JOIN sources s ON a.sourceId=s.id WHERE s.research=1 AND a.effectiveAt>:fromAt AND a.effectiveAt<=:toAt AND (:allSources=1 OR s.id IN (:sourceIds)) AND a.language IN (:languages) AND (:topicPattern='' OR a.title LIKE :topicPattern ESCAPE '!' OR a.body LIKE :topicPattern ESCAPE '!' OR a.title LIKE :alternatePattern ESCAPE '!' OR a.body LIKE :alternatePattern ESCAPE '!') ORDER BY a.effectiveAt DESC,a.id LIMIT 5000") suspend fun trendArticles(fromAt:Long,toAt:Long,allSources:Boolean,sourceIds:List<String>,languages:List<String>,topicPattern:String,alternatePattern:String):List<TrendArticle>
 @Query("SELECT COUNT(*) AS sourceCount,COUNT(CASE WHEN s.lastSuccess<:currentStart THEN 1 END) AS staleCount,MAX(s.lastSuccess) AS lastRefresh FROM sources s WHERE s.research=1 AND (:allSources=1 OR s.id IN (:sourceIds))") suspend fun trendFeedHealth(currentStart:Long,allSources:Boolean,sourceIds:List<String>):TrendFeedHealth
 @Query("SELECT s.id,s.lastSuccess FROM sources s WHERE s.research=1 AND (:allSources=1 OR s.id IN (:sourceIds))") suspend fun trendSourceRefresh(allSources:Boolean,sourceIds:List<String>):List<TrendSourceRefresh>
 @Query("SELECT c.* FROM chunks c JOIN articles a ON a.id=c.articleId JOIN sources s ON s.id=a.sourceId WHERE s.enabled=1 AND a.effectiveAt>=:cutoff AND (c.embedding IS NULL OR c.embeddingModel!=:model) LIMIT 100") suspend fun pending(cutoff:Long,model:String):List<ChunkEntity>
 @Query("UPDATE chunks SET embedding=:bytes,embeddingModel=:model WHERE id=:id AND articleId IN (SELECT a.id FROM articles a JOIN sources s ON s.id=a.sourceId WHERE s.enabled=1 AND a.effectiveAt>=:cutoff)") suspend fun vector(id:String,bytes:ByteArray,model:String,cutoff:Long)
 @Insert suspend fun addReport(r:ReportEntity)
 @Query("SELECT * FROM reports WHERE id=:id") suspend fun report(id:String):ReportEntity?
 @Insert suspend fun addEvidence(e:List<EvidenceEntity>)
 @Query("UPDATE reports SET text=:text,status=:status,actualModel=:model,error=:error WHERE id=:id") suspend fun finish(id:String,text:String,status:String,model:String,error:String)
 @Query("SELECT * FROM reports ORDER BY createdAt DESC") fun reports():Flow<List<ReportEntity>>
 @Query("SELECT * FROM evidence WHERE reportId=:id ORDER BY citation") suspend fun evidence(id:String):List<EvidenceEntity>
 @Query("DELETE FROM reports WHERE id=:id") suspend fun deleteReport(id:String)
 @Query("DELETE FROM reports WHERE id NOT IN (SELECT id FROM reports ORDER BY createdAt DESC LIMIT 100) AND status!='RUNNING'") suspend fun capReports()
 @Query("UPDATE reports SET status='PARTIAL',error='App process ended before completion' WHERE status='RUNNING'") suspend fun recover()
}
@Database(entities=[SourceEntity::class,ArticleEntity::class,ChunkEntity::class,ReportEntity::class,EvidenceEntity::class,SeenEntity::class],version=4,exportSchema=true)
abstract class AppDatabase:RoomDatabase(){abstract fun dao():OsintDao
 companion object{
  val MIGRATION_1_2 = object : Migration(1, 2) {
   override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE articles ADD COLUMN contentType TEXT NOT NULL DEFAULT 'UNKNOWN'")
    db.execSQL("ALTER TABLE evidence ADD COLUMN contentType TEXT NOT NULL DEFAULT 'UNKNOWN'")
   }
  }
  val MIGRATION_2_3 = object : Migration(2, 3) {
   override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE evidence ADD COLUMN firstSeenAt INTEGER")
   }
  }
  val MIGRATION_3_4 = object : Migration(3, 4) {
   override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE sources ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE articles ADD COLUMN language TEXT NOT NULL DEFAULT 'und'")
   }
  }
  fun create(c:Context)=Room.databaseBuilder(c,AppDatabase::class.java,"osint.db")
   .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4)
   .build()
 }}