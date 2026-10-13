package com.example.mobileosint
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlin.math.ln

data class ResearchFilters(val fromAt:Long?=null,val toAt:Long?=null,val sourceIds:Set<String> = emptySet(),val publishers:Set<String> = emptySet(),val topic:String="",val languages:Set<String> = setOf("pt","en","und"),val trendMinutes:Int=60)
enum class ResearchLanguageMode(val label:String,val languageCodes:Set<String>){
 PORTUGUESE("Portuguese",setOf("pt")),ENGLISH("English",setOf("en","und")),BOTH("Both",setOf("pt","en","und"));
 companion object{fun fromCodes(codes:Set<String>)=entries.firstOrNull{it.languageCodes==codes}?:BOTH}
}
data class TrendSupport(val title:String,val url:String,val publisher:String,val publishedAt:Long?,val firstSeenAt:Long,val excerpt:String)
data class TrendKeyword(val key:String,val label:String,val currentArticles:Int,val currentPublishers:Int,val previousArticles:Int,val previousPublishers:Int,val status:String,val supports:List<TrendSupport>)
data class KeywordReport(val kind:String="keyword-trends-v2",val start:Long,val end:Long,val previousStart:Long,val currentArticles:Int,val currentPublishers:Int,val currentUndated:Int,val previousArticles:Int,val previousPublishers:Int,val previousUndated:Int,val staleFeeds:Int,val sourceCount:Int,val lastRefresh:Long?,val baselineSparse:Boolean,val filters:String,val keywords:List<TrendKeyword>)
data class TrendSummary(val text:String,val diagnostics:String,val supporting:List<TrendArticle>,val report:KeywordReport)
object KeywordReportCodec {
 private val gson=Gson()
 fun encode(report:KeywordReport):String=gson.toJson(report)
 fun decode(text:String):KeywordReport?=if(!text.startsWith("{"))null else runCatching{
  if(JsonParser.parseString(text).asJsonObject.get("kind")?.asString=="keyword-trends-v2")gson.fromJson(text,KeywordReport::class.java) else null
 }.getOrNull()
}

object KeywordTrends {
 data class Window(val start:Long,val end:Long,val precedingStart:Long,val baselineComplete:Boolean)
 fun window(now:Long,retentionStart:Long,filters:ResearchFilters):Window{
  require(filters.trendMinutes in 15..1440){"Trend window must be 15-1440 minutes."}
  val end=minOf(now,filters.toAt?:now)
  val start=maxOf(end-filters.trendMinutes*60_000L,filters.fromAt?:Long.MIN_VALUE,retentionStart)
  require(start<end){"No recent time window remains within the selected dates and retention."}
  val precedingStart=start-(end-start)
  return Window(start,end,precedingStart,precedingStart>=retentionStart)
 }
 private val englishStop=("a an and are as at be been before but by can did do does for from had has have how if in into is it its may of on one or other our out over per s that their the there these this those to us was week weeks were what when where which while who will with would year years you your "+
  "about after also all any each even first here just more most much now only said say she some than them then they through too under up very we were why yet could should would might likely meanwhile reports reported says").split(' ').toSet()
 private val portugueseStop=("a ano anos as ao aos apos ate com como da das de dela dele depois deve devem do dos e em enquanto entre essa esse esta estao estava estavam este foi foram ha isso ja mais mas na nas nao nesta neste no nos o os ou para pela pelo por porque que sao se sem seu sua seus suas ser sera serao sendo sobre tambem tem ter um uma uns umas presidente "+
  "ainda alguns algumas antes assim ate cada contra debaixo desde dia dias durante eles elas ela ele era eram essa essas esse esses estas estes eu hoje inicio mesmo muito muita muitos muitas nossa nossas nosso nossos onde pela pelas pelo pelos pode podem poderia podera pois qual quando quem se serao sido sob suas todo toda todos todas vai vao voce voces").split(' ').toSet()
 private val noise=("article articles breaking click continue daily edition feed feeds headline headlines homepage latest live menu more new news newsletter read report reports reported said says share source sources story stories subscribe today top trending trend trends update updates yesterday " +
  "assine artigo artigos confira desta desta-feira diz disse fonte fontes hoje jornal leia noticia noticias novo nova novos novas pagina publicidade saiba segundo veja segunda-feira terca-feira quarta-feira quinta-feira sexta-feira sabado domingo monday tuesday wednesday thursday friday saturday sunday photograph getty images photo credits copyright").split(' ').toSet()
 private val boilerplate=Regex("(?i)\\b(?:ap news|europe live|breaking news|latest news|read more|continue reading|click here|share this|skip to content|leia mais|saiba mais|veja tamb[eé]m)\\b")
 private val markup=Regex("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)>|<[^>]+>|&(?:#\\d+|#x[0-9a-f]+|[a-z]+);")
 private val urls=Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>]+")
 private val feedPrefix=Regex("(?i)^\\s*arxiv:\\S+\\s+announce type:\\s*\\w+\\s+abstract:\\s*")
 private val word=Regex("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*")
 private val acronym=Regex("[\\p{Lu}]{2,6}")
 private fun key(value:String)=Normalizer.normalize(value.lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")
 private fun clean(value:String)=value.replace(feedPrefix,"").replace(urls," ").replace(markup," ").replace(boilerplate," ")
 internal fun terms(article:TrendArticle):Map<String,String>{
  val result=linkedMapOf<String,String>()
  val stops=when(article.language){"pt"->portugueseStop;"en"->englishStop;else->portugueseStop+englishStop}
  fun allowed(value:String,following:Char?):Boolean{
   val normalized=key(value)
   if(normalized in noise||!normalized.any{it.isLetter()}||value.length<3&&!acronym.matches(value))return false
   if(normalized !in stops)return true
   return value=="US"&&following!='$'
  }
  fun collect(text:String){
   val matches=word.findAll(text).toList()
   matches.forEach{match->if(allowed(match.value,text.drop(match.range.last+1).trimStart().firstOrNull()))result.putIfAbsent(key(match.value),match.value)}
  for(size in 2..minOf(3,matches.size))for(index in 0..matches.size-size){
    val group=matches.subList(index,index+size)
    if(group.zipWithNext().any{(first,second)->text.substring(first.range.last+1,second.range.first).isNotBlank()})continue
    val first=group.first().value;val last=group.last().value
    val named=first.first().isUpperCase()&&last.first().isUpperCase()
    if(!allowed(first,text.drop(group.first().range.last+1).trimStart().firstOrNull())&&!(named&&key(first) in setOf("sao","one","presidente"))||
     !allowed(last,text.drop(group.last().range.last+1).trimStart().firstOrNull())&&!(named&&key(last) in setOf("week","weeks")))continue
    if(group.drop(1).dropLast(1).any{key(it.value) in noise||it.value.all(Char::isDigit)})continue
    val phrase=group.joinToString(" "){it.value}
    result.putIfAbsent(key(phrase),phrase)
   }
  }
  collect(clean(article.title))
  collect(clean(article.body).take(320))
  return result
 }
 private fun excerpt(article:TrendArticle,term:String):String{
  if(term in terms(article.copy(body="")))return article.title
  val body=clean(article.body).take(320)
  val matches=word.findAll(body).toList()
  val parts=term.split(' ')
  val first=matches.indices.firstOrNull{index->index+parts.size<=matches.size&&parts.indices.all{offset->
   val match=matches[index+offset]
   key(match.value)==parts[offset]&&(term!="us"||match.value=="US"&&body.drop(match.range.last+1).trimStart().firstOrNull()!='$')&&
    (offset==0||body.substring(matches[index+offset-1].range.last+1,match.range.first).isBlank())
  }}
  return if(first==null)body.take(160) else body.substring((matches[first].range.first-65).coerceAtLeast(0),(matches[first+parts.size-1].range.last+66).coerceAtMost(body.length))
 }
 private fun publisher(article:TrendArticle)=LocalRetriever.publisher(article.sourceUrl)
 private fun index(articles:List<TrendArticle>):Map<String,List<TrendArticle>> =
  articles.flatMap{article->terms(article).keys.map{it to article}}.groupBy({it.first},{it.second})
 private fun publisherCount(articles:List<TrendArticle>)=articles.mapNotNull(::publisher).distinct().size
 fun analyze(current:List<TrendArticle>,previous:List<TrendArticle>,health:TrendFeedHealth,start:Long,end:Long,filters:String,baselineComplete:Boolean=true,sourceRefresh:Map<String,Long> = emptyMap()):TrendSummary{
  val recent=current.distinctBy{it.id};val earlier=previous.distinctBy{it.id}
  val currentIndex=index(recent);val previousIndex=index(earlier)
  val undatedRecent=recent.count{it.publishedAt==null};val undatedEarlier=earlier.count{it.publishedAt==null}
  val baselineSparse=!baselineComplete||earlier.size<3||publisherCount(earlier)<2
  val stale=health.staleCount>0
  val coverage="${recent.size} articles / ${publisherCount(recent)} publisher hosts (previous: ${earlier.size} articles / ${publisherCount(earlier)} publisher hosts)"
  val lines=mutableListOf("Keyword coverage in indexed feeds (local, not web-wide)",
   "Current: ${java.time.Instant.ofEpochMilli(start)} to ${java.time.Instant.ofEpochMilli(end)}: ${recent.size} articles, ${publisherCount(recent)} known publisher hosts, $undatedRecent without publication dates.",
   "Previous equal window: ${java.time.Instant.ofEpochMilli(start-(end-start))} to ${java.time.Instant.ofEpochMilli(start)}: ${earlier.size} articles, ${publisherCount(earlier)} known publisher hosts, $undatedEarlier without publication dates.",
   "Source freshness: ${health.staleCount}/${health.sourceCount} feeds not successfully refreshed in the current window; last success: ${health.lastRefresh?.takeIf{it>0}?.let{java.time.Instant.ofEpochMilli(it)}?:"never"}.")
  if(baselineSparse)lines.add("Sparse baseline: too few preceding articles or publishers, or the preceding window extends beyond retained history; no trend can be established.")
  if(stale)lines.add("Stale or unrefreshed feed coverage: apparent increases may reflect ingestion timing, not news activity.")
  val ranked=mutableListOf<Map.Entry<String,List<TrendArticle>>>()
  val repeated=currentIndex.values.any{it.size>=2&&publisherCount(it)>=2}
  fun score(entry:Map.Entry<String,List<TrendArticle>>):Double{
   val documents=recent.size.toDouble();val frequency=entry.value.size.toDouble()
   val idf=ln(1.0+(documents-frequency+0.5)/(frequency+0.5))
   val words=entry.key.split(' ')
   val phraseWeight=if(words.size==1)1.0 else {
    val endpoints=listOf(words.first(),words.last())
    val expected=endpoints.fold(documents){estimate,token->estimate*(currentIndex[token]?.size?:0)/documents}
    1.1+ln((frequency/expected.coerceAtLeast(1.0)).coerceAtLeast(1.0)).coerceAtMost(1.0)
   }
  val acronymWeight=if(entry.key in setOf("us","eu","ia","df"))1.25 else 1.0
  return (frequency*idf+if(documents<5)frequency*0.6 else 0.0)*phraseWeight*acronymWeight
  }
  val candidates=currentIndex.entries.filter{!repeated||it.value.size>=2&&publisherCount(it.value)>=2}
    .sortedWith(compareByDescending<Map.Entry<String,List<TrendArticle>>>(::score).thenByDescending{it.value.size}.thenByDescending{publisherCount(it.value)}.thenByDescending{it.key.split(' ').size}.thenBy{it.key})
  for(candidate in candidates){
   val candidateIds=candidate.value.map{it.id}.toSet();val candidatePrior=previousIndex[candidate.key].orEmpty().map{it.id}.toSet()
   val overlap=ranked.any{chosen->
    val chosenIds=chosen.value.map{it.id}.toSet();val chosenPrior=previousIndex[chosen.key].orEmpty().map{it.id}.toSet()
    chosen.key.split(' ').size>1&&candidate.key.split(' ').any{it in chosen.key.split(' ')}&&candidateIds.size>=2&&publisherCount(candidate.value)>=2&&
     candidateIds.intersect(chosenIds).size.toDouble()/candidateIds.union(chosenIds).size>=0.8&&
     (candidatePrior.isEmpty()&&chosenPrior.isEmpty()||candidatePrior.isNotEmpty()&&chosenPrior.isNotEmpty()&&candidatePrior.intersect(chosenPrior).size.toDouble()/candidatePrior.union(chosenPrior).size>=0.8)
   }
   if(!overlap)ranked.add(candidate)
   if(ranked.size==8)break
  }
  val supporting=mutableListOf<TrendArticle>()
  val keywords=mutableListOf<TrendKeyword>()
  if(ranked.isEmpty())lines.add("No indexed articles in the current window; no keyword trend can be assessed.")
  for((term,articles) in ranked){
   val prior=previousIndex[term].orEmpty()
   val currentPublishers=publisherCount(articles);val previousPublishers=publisherCount(prior)
   val missingDates=articles.any{it.publishedAt==null}||prior.any{it.publishedAt==null}
  val staleRelevant=stale&&(sourceRefresh.isEmpty()||(articles+prior).any{(sourceRefresh[it.sourceId]?:Long.MIN_VALUE)<start})
   val status=when{
    baselineSparse||prior.size<2||previousPublishers<2->"sparse baseline; no verified trend"
  staleRelevant->"stale feed coverage; no verified trend (supporting sources stale)"
    missingDates->"publication dates missing; no verified trend"
    articles.size<3||currentPublishers<2->"insufficient current coverage"
   articles.size>prior.size&&currentPublishers>=previousPublishers->if(stale)"increase in indexed coverage among refreshed supporting feeds; wider coverage uncertain" else "increase in indexed coverage"
    else->"no supported increase"
   }
  val label=terms(articles.first())[term]?:term
  lines.add("\n$label: ${articles.size} articles / $currentPublishers publishers; previous ${prior.size} articles / $previousPublishers publishers. $status.")
  keywords.add(TrendKeyword(term,label,articles.size,currentPublishers,prior.size,previousPublishers,status,articles.take(100).map{article->TrendSupport(article.title,article.url,publisher(article)?:"publisher unknown",article.publishedAt,article.firstSeenAt,excerpt(article,term))}))
  articles.distinctBy{publisher(it)?:"feed:${it.sourceId}"}.take(2).forEach{article->
    lines.add("- ${article.title} (${publisher(article)?:"publisher unknown"}; ${if(article.publishedAt!=null)"published ${java.time.Instant.ofEpochMilli(article.publishedAt)}" else "first seen ${java.time.Instant.ofEpochMilli(article.firstSeenAt)}, publication unknown"})\n  ${article.url}")
    supporting.add(article)
   }
  }
  val report=KeywordReport(start=start,end=end,previousStart=start-(end-start),currentArticles=recent.size,currentPublishers=publisherCount(recent),currentUndated=undatedRecent,previousArticles=earlier.size,previousPublishers=publisherCount(earlier),previousUndated=undatedEarlier,staleFeeds=health.staleCount,sourceCount=health.sourceCount,lastRefresh=health.lastRefresh,baselineSparse=baselineSparse,filters=filters,keywords=keywords)
  return TrendSummary(lines.joinToString("\n"),"Local calculation; no OpenRouter call | $filters | $coverage | baseline=${if(baselineSparse)"sparse" else "measured"}, undated=$undatedRecent/$undatedEarlier, stale feeds=${health.staleCount}/${health.sourceCount}",supporting.distinctBy{it.id},report)
 }
}

object LocalRetriever {
 fun publisher(url:String):String?=runCatching{URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")}.getOrNull()?.takeIf{it.isNotBlank()}
 fun selectedSourceIds(sources:List<SourceEntity>,filters:ResearchFilters)=sources.filter{
  (filters.sourceIds.isEmpty()||it.id in filters.sourceIds)&&(filters.publishers.isEmpty()||publisher(it.url) in filters.publishers)
 }.map{it.id}
 fun publisherKey(doc:Candidate)=publisher(doc.sourceUrl)?:"feed:${doc.sourceId}"
 fun literalPattern(value:String)="%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%"
 fun topicPatterns(topic:String):Pair<String,String>{
  if(topic.isBlank())return "" to ""
  val literal=topic.trim().take(80)
  val normalized=Algorithms.tokens(literal).singleOrNull()?:literal.lowercase(Locale.ROOT)
  val alternate=Algorithms.aliases(normalized).firstOrNull{it!=normalized}?:literal
  return literalPattern(literal) to literalPattern(alternate)
 }
 data class Selection(val ranked:List<Int>,val matchedArticles:Int,val reasons:Map<Int,String>,val limitation:String="")
 fun rank(question:String,docs:List<Candidate>):Selection{
  val latest=Algorithms.latestTopic(question)
  if(latest=="")return Selection(NewsRanking.rank(docs,"",emptyList()),docs.map{it.articleId}.distinct().size,emptyMap())
  val query=latest?:question
  val terms=Algorithms.searchTokens(query).distinct()
  if(terms.isEmpty())return Selection(emptyList(),0,emptyMap(),"No distinctive local search terms. Add a specific person, place, event, or topic.")
  val scores=Algorithms.bm25Scores(query,docs.map{it.title+" "+it.title+" "+it.text})
  val reasons=mutableMapOf<Int,String>()
  val scored=docs.indices.mapNotNull{index->
   if(scores[index]<=0)return@mapNotNull null
   val title=Algorithms.searchTokens(docs[index].title).toSet()
   val body=Algorithms.searchTokens(docs[index].text).toSet()
   val hits=terms.count{it in title||it in body}
   if(hits<minOf(2,terms.size))return@mapNotNull null
   val titleHits=terms.count{it in title}
  if(terms.size>=3&&hits<terms.size&&titleHits<2)return@mapNotNull null
   val tagHint=Algorithms.searchTokens(docs[index].tags).any{it in terms}
   val score=scores[index]*(1+titleHits*0.4+(if(tagHint)0.08 else 0.0))
   reasons[index]="${hits}/${terms.size} terms, $titleHits in title${if(tagHint) ", source-tag hint" else ""}"
   index to score
  }.sortedWith(compareByDescending<Pair<Int,Double>>{it.second}.thenByDescending{docs[it.first].effectiveAt})
  val unique=scored.distinctBy{docs[it.first].articleId}
  val ranked=if(latest!=null){
   val peak=unique.firstOrNull()?.second?:0.0
   unique.filter{it.second>=peak*0.4}.sortedWith(compareByDescending<Pair<Int,Double>>{it.first.let{index->docs[index].effectiveAt}}.thenByDescending{it.second})
  }else unique
  return Selection(ranked.take(100).map{it.first},unique.size,reasons,if(unique.isEmpty())"Local lexical matching found no sufficiently supported article; it cannot infer arbitrary cross-language meaning." else "")
 }
}

data class FeedImportState(val total:Int=0,val completed:Int=0,val added:Int=0,val skipped:Int=0,val failed:Int=0,val failures:List<String> = emptyList(),val running:Boolean=false)

object RefreshRunner {
 suspend fun <T,R> run(items:List<T>,limit:Int,fetch:suspend (T)->R,apply:suspend (T,Result<R>)->Unit) = coroutineScope {
  for(batch in items.chunked(limit.coerceIn(1,100))){
   val results=batch.map{item->async {
    val result=try{Result.success(fetch(item))}catch(e:CancellationException){throw e}catch(e:Exception){Result.failure(e)}
    item to result
   }}.awaitAll()
   for((item,result) in results){ensureActive();apply(item,result)}
  }
 }
}
fun sourceStillCurrent(current:SourceEntity?,snapshot:SourceEntity)=current!=null&&current.enabled&&current.revision==snapshot.revision

object FeedImporter {
 suspend fun run(input:String,existing:Set<String>,add:suspend (String)->Boolean,onProgress:(FeedImportState)->Unit):FeedImportState {
  val urls=input.lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.toList()
  val seen=existing.toMutableSet()
  val pending=urls.filter{seen.add(it)}
  var state=FeedImportState(total=urls.size,completed=urls.size-pending.size,skipped=urls.size-pending.size,running=true)
  onProgress(state)
  val stateLock=Mutex()
  coroutineScope {
   for(batch in pending.chunked(3)) {
    batch.map{url->async {
     val result=try{(if(add(url)) 1 else 0) to ""}catch(e:CancellationException){throw e}catch(e:Exception){-1 to (e.message?.take(200)?:e.javaClass.simpleName)}
     stateLock.withLock {
      state=when(result.first){
       1->state.copy(completed=state.completed+1,added=state.added+1)
       0->state.copy(completed=state.completed+1,skipped=state.skipped+1)
       else->state.copy(completed=state.completed+1,failed=state.failed+1,failures=state.failures+"$url: ${result.second}")
      }
      onProgress(state)
     }
    }}.awaitAll()
   }
  }
  return state.copy(running=false).also(onProgress)
 }
}

data class EvidencePlan(val evidence:List<EvidenceEntity>,val estimatedInputTokens:Int,val contextLimit:Int,val outputLimit:Int,val budgetLimited:Int,val clipped:Int)

object EvidencePlanner {
 fun estimateTokens(text:String)=(text.length+1)/2
 fun plan(docs:List<Candidate>,ranked:List<Int>,bodies:Map<String,String>,config:Config,question:String,reportId:String,now:Long,maxExcerptChars:Int=6000):EvidencePlan {
  val unknown=config.model=="openrouter/auto"||config.fallbacks.isNotBlank()
  val limit=config.contextTokens.coerceIn(4096,1048576).let{if(unknown)minOf(it,8192) else it}
  val output=config.outputTokens.coerceIn(256,4000)
  var estimated=1200+estimateTokens(question)+64
  val shortlist=ranked.take(minOf(100,config.topK.coerceIn(1,24)*6)).map{docs[it]}
  val first=shortlist.distinctBy{LocalRetriever.publisherKey(it)}
  val second=shortlist.filterNot{it in first}.distinctBy{it.sourceId}
  val ordered=first+second+shortlist.filterNot{it in first||it in second}
  val selected=mutableListOf<EvidenceEntity>();val hashes=mutableSetOf<String>();val articleIds=mutableSetOf<String>()
  val excerpts=mutableSetOf<String>();var budgetLimited=0;var clipped=0
  for(candidate in ordered){
   if(selected.size>=config.topK.coerceIn(1,24))break
   if(candidate.articleId in articleIds||candidate.hash in hashes)continue
  val excerpt=candidate.text.substringAfter('\n',candidate.text).trim().lowercase().replace(Regex("\\s+")," ")
  if(excerpt in excerpts)continue
   val overhead=estimateTokens(candidate.title+candidate.source+candidate.url)+64
   val available=(limit-output-estimated-overhead)*2
   if(available<160){budgetLimited++;continue}
  val body=bodies[candidate.articleId]?.takeIf{it.isNotEmpty()}?:candidate.text
  val size=minOf(available,maxExcerptChars,body.length)
   val needle=candidate.text.substringAfter('\n').take(80)
   val match=if(needle.isNotBlank())body.indexOf(needle) else -1
   val start=if(match<0)0 else (match-400).coerceIn(0,(body.length-size).coerceAtLeast(0))
   val text=body.substring(start,start+size)
   estimated+=overhead+estimateTokens(text)
   if(text.length<body.length||candidate.contentType=="CLIPPED")clipped++
  selected.add(EvidenceEntity("$reportId:${selected.size}",reportId,selected.size+1,candidate.title,candidate.url,candidate.source,text,candidate.publishedAt,now,candidate.hash,candidate.contentType,candidate.firstSeenAt.takeIf{it>0}))
  hashes.add(candidate.hash);articleIds.add(candidate.articleId);excerpts.add(excerpt)
  }
  return EvidencePlan(selected,estimated,limit,output,budgetLimited,clipped)
 }
}

object NewsRanking {
 fun rank(docs:List<Candidate>,topic:String,lexical:List<Int>):List<Int> =
  if(topic.isBlank())docs.indices.distinctBy{docs[it].articleId}.take(100)
  else lexical.distinctBy{docs[it].articleId}.sortedByDescending{docs[it].effectiveAt}
}

fun generationStatus(text:String,finishReason:String,isDone:Boolean,streamError:String)=when{
 streamError.isNotBlank()->if(text.isBlank()) "FAILED" else "PARTIAL"
 finishReason=="length"->if(text.isBlank()) "FAILED" else "TRUNCATED"
 (isDone||finishReason=="stop")&&text.isNotBlank()->"COMPLETE"
 text.isNotBlank()->"PARTIAL"
 else->"FAILED"
}

interface VectorStore { fun rank(query:FloatArray,docs:List<Candidate>,model:String):List<Int> }
class RoomFlatVectorStore:VectorStore {
 override fun rank(query:FloatArray,docs:List<Candidate>,model:String)=docs.indices
  .filter{docs[it].embedding!=null&&docs[it].embeddingModel==model}
  .map{it to Algorithms.cosine(query,Algorithms.decode(docs[it].embedding!!))}
  .filter{it.second>0.15}.sortedByDescending{it.second}.take(100).map{it.first}
}
class ResearchService(val db:AppDatabase,val settings:Settings){
 val dao=db.dao();private val rss=RssProvider();private val embed=EmbeddingProvider();private val ai:AiProvider=OpenRouterProvider();private val vectors:VectorStore=RoomFlatVectorStore()
 private val syncLock=Mutex()
 val progress=MutableStateFlow("Ready")
 val ready=CompletableDeferred<Unit>()
 suspend fun initialize(){try{dao.recover();cleanup();backfillLanguages()}finally{ready.complete(Unit)}}
 suspend fun backfillLanguages(){
  val updates=withContext(Dispatchers.Default){dao.unclassifiedArticles().mapNotNull{article->Algorithms.detectLanguage(article.title+" "+article.body).takeIf{it!="und"}?.let{article.id to it}}}
  db.withTransaction{for((id,language) in updates)dao.setArticleLanguage(id,language)}
 }
 suspend fun addSource(url:String){ready.await();val clean=url.trim();require(clean.startsWith("https://")){"Use an HTTPS RSS/Atom URL"}
  val feed=withContext(Dispatchers.IO){rss.fetch(clean)}
  db.withTransaction{require(dao.sourceCount()<100){"Maximum 100 feeds"};dao.addSource(SourceEntity(UUID.randomUUID().toString(),clean,feed.title))};progress.value="Feed added. Tap Sync."
 }
 suspend fun importFeeds(input:String,onProgress:(FeedImportState)->Unit):FeedImportState {
  ready.await()
  return FeedImporter.run(input,dao.sourceUrls().toSet(),{url->
   if(dao.hasSourceUrl(url)>0||dao.sourceCount()>=100) false
   else try{addSource(url);true}catch(e:CancellationException){throw e}catch(e:Exception){
    if(dao.hasSourceUrl(url)>0||dao.sourceCount()>=100) false else throw e
   }
  },onProgress)
 }
 suspend fun toggle(id:String,fetch:Boolean?=null,research:Boolean?=null){db.withTransaction{val s=dao.source(id)?:return@withTransaction;dao.updateSource(s.copy(enabled=fetch?:s.enabled,research=research?:s.research,revision=s.revision+1))}}
 suspend fun setTags(id:String,value:String){require(value.length<=200&&'\n' !in value){"Tags must be one comma-separated line (up to 200 characters)"};db.withTransaction{val s=dao.source(id)?:return@withTransaction;dao.updateSource(s.copy(tags=value.split(',').map{it.trim()}.filter{it.isNotBlank()}.distinct().joinToString(", ")))}}
 suspend fun setAllFeedsEnabled(enabled:Boolean){ready.await();db.withTransaction{dao.setAllFetchEnabled(enabled)}}
 suspend fun deleteSource(id:String){db.withTransaction{dao.deleteSource(id)}}
 suspend fun cleanup(){val c=settings.config();db.withTransaction{dao.expire(Algorithms.cutoff(System.currentTimeMillis(),c.days));dao.cap(c.articleCap);dao.capReports();dao.capSeen()}}
 suspend fun sync():Boolean=syncLock.withLock{
  ready.await();val c=settings.config();cleanup();val active=dao.active();var successes=0;var completed=0;var failures=0
  val started=System.nanoTime()
  try{
  RefreshRunner.run(active,c.refreshConcurrency,{source->withContext(Dispatchers.IO){rss.fetch(source.url)}}){source,result->
    try{
     val feed=result.getOrThrow();val now=System.currentTimeMillis();val cutoff=Algorithms.cutoff(now,c.days)
    val parsedCount=feed.items.size;val clippedCount=feed.items.count{it.contentType=="CLIPPED"};var expiredCount=0;var unchangedCount=0;var insertedCount=0;var updatedCount=0
     db.withTransaction{
      val current=dao.source(source.id)
      if(current==null||!sourceStillCurrent(current,source))return@withTransaction
      for(item in feed.items){
       val id=Algorithms.hash(source.id+":"+item.identity);val old=dao.article(id)
       val first=dao.seen(id)?.firstSeenAt?:old?.firstSeenAt?:now;dao.remember(SeenEntity(id,source.id,first));val published=item.publishedAt?.takeIf{it<=now+300_000L}
       val effective=published?:first
       if(effective<cutoff){expiredCount++;continue}
       val hash=Algorithms.hash(item.title+item.text)
       if(old==null){insertedCount++} else if(old.hash!=hash){updatedCount++} else {unchangedCount++}
       if(old?.hash!=hash){
        val generatedChunks=Algorithms.chunks(item.title+"\n"+item.text)
        Log.d("MobileOsintDiag","Article id=$id, length=${item.text.length}, type=${item.contentType}, chunks=${generatedChunks.size}")
        dao.putArticle(ArticleEntity(id,source.id,item.title,item.url,item.text,published,first,effective,hash,item.contentType,Algorithms.detectLanguage(item.title+" "+item.text)))
          dao.deleteChunks(id)
        dao.putChunks(generatedChunks.mapIndexed{i,text->ChunkEntity("$id:$i",id,text)})
       }
      }
      val statusStr=when{
       parsedCount==0->"EMPTY_FEED: 0 items parsed"
       insertedCount>0||updatedCount>0->"SUCCESS: $insertedCount new, $updatedCount updated ($parsedCount parsed)"
       expiredCount==parsedCount->"ALL_EXPIRED: $parsedCount parsed, all older than ${c.days}d retention"
       else->"UNCHANGED: $parsedCount parsed, 0 new items"
      }
      Log.d("MobileOsintDiag","Source ${source.id} status: $statusStr (parsed=$parsedCount, expired=$expiredCount, unchanged=$unchangedCount, inserted=$insertedCount, updated=$updatedCount)")
      val isOk=insertedCount>0||updatedCount>0||unchangedCount>0
      dao.updateSource(current.copy(lastAttempt=now,lastSuccess=if(isOk) now else current.lastSuccess,status=(statusStr+if(clippedCount>0)"; $clippedCount clipped" else ""),failures=if(isOk) 0 else current.failures+1));dao.cap(c.articleCap);dao.capSeen()
     };if(insertedCount>0||updatedCount>0||unchangedCount>0) successes++
    }catch(e:CancellationException){throw e}catch(e:Exception){failures++
     Log.e("MobileOsintDiag","Sync failed for ${source.id}",e)
     db.withTransaction{
      val s=dao.source(source.id)
      if(s!=null&&sourceStillCurrent(s,source)){
       val errStatus="${e.javaClass.simpleName}: ${e.message?:"Fetch failed"}".take(200)
       dao.updateSource(s.copy(lastAttempt=System.currentTimeMillis(),status=errStatus,failures=s.failures+1))
      }
     }
    }finally{completed++;progress.value="Refreshed $completed/${active.size} feeds ($failures failed)"}
   }
   if(c.embeddings){val key=settings.apiKey();if(key.isNotBlank()){
    progress.value="Indexing up to 100 pending chunks"
    try{for(batch in dao.pending(Algorithms.cutoff(System.currentTimeMillis(),c.days),c.embeddingModel).chunked(16)){
     val vs=embed.embed(key,c.embeddingModel,batch.map{it.text})
     db.withTransaction{batch.forEachIndexed{i,ch->dao.vector(ch.id,Algorithms.encode(vs[i]),c.embeddingModel,Algorithms.cutoff(System.currentTimeMillis(),c.days))}}
    }}catch(e:CancellationException){throw e}catch(e:Exception){progress.value="Feeds synced; embedding error: ${e.message}";return@withLock successes>0||active.isEmpty()}
   }}
  cleanup();progress.value="Sync finished: $successes/${active.size} feeds, $failures failed, ${(System.nanoTime()-started)/1_000_000} ms";successes>0||active.isEmpty()
  }finally{if(progress.value.startsWith("Refreshed")||progress.value.startsWith("Indexing"))progress.value="Sync stopped"}
 }
 suspend fun research(question:String,onUpdate:(String)->Unit,filters:ResearchFilters=ResearchFilters()):String{
  ready.await();require(question.isNotBlank()&&question.length<=2000){"Question must contain 1-2000 characters"}
  val c=settings.config();val now=System.currentTimeMillis()
  val fromAt=maxOf(Algorithms.cutoff(now,c.days),filters.fromAt?:0L)
  val toAt=minOf(now+300_000L,filters.toAt?:now+300_000L)
  require(fromAt<=toAt){"The selected date window has no days within retention."}
  require(filters.languages.isNotEmpty()&&filters.languages.all{it in setOf("pt","en","und")}){"Select at least one supported language."}
  val sources=if(filters.sourceIds.isNotEmpty()||filters.publishers.isNotEmpty())dao.sources().first() else emptyList()
  val allowed=LocalRetriever.selectedSourceIds(sources,filters)
  val allSources=filters.sourceIds.isEmpty()&&filters.publishers.isEmpty()
  val (pattern,alternate)=LocalRetriever.topicPatterns(filters.topic)
  if(Algorithms.keywordTrendIntent(question)){
   val retentionStart=Algorithms.cutoff(now,c.days)
    val window=KeywordTrends.window(now,retentionStart,filters)
    val (start,end,precedingStart,baselineComplete)=window
   val languages=filters.languages.toList()
   val recent=dao.trendArticles(start,end,allSources,allowed,languages,pattern,alternate)
   val preceding=dao.trendArticles(precedingStart,start,allSources,allowed,languages,pattern,alternate)
   val health=dao.trendFeedHealth(start,allSources,allowed)
  val refresh=dao.trendSourceRefresh(allSources,allowed).associate{it.id to it.lastSuccess}
   val filterNote="sources=${if(filters.sourceIds.isEmpty())"all" else filters.sourceIds.size}, publishers=${if(filters.publishers.isEmpty())"all" else filters.publishers.joinToString(",")}, topic=${filters.topic.ifBlank{"none"}}, languages=${filters.languages.sorted().joinToString(",")}, window=${(end-start)/60_000} minutes"
  val summary=withContext(Dispatchers.Default){KeywordTrends.analyze(recent,preceding,health,start,end,filterNote,baselineComplete,refresh)}
   val id=UUID.randomUUID().toString()
   val evidence=summary.supporting.take(c.topK.coerceIn(1,24)).mapIndexed{index,article->
    EvidenceEntity("$id:$index",id,index+1,article.title,article.url,article.source,(article.title+"\n"+article.body).take(1000),article.publishedAt,now,Algorithms.hash(article.title+article.body),"TREND_REFERENCE",article.firstSeenAt)
   }
   db.withTransaction{
    dao.addReport(ReportEntity(id,question,KeywordReportCodec.encode(summary.report),"COMPLETE","local/keyword-trends","local/keyword-trends",now,summary.diagnostics,"keyword-trends-v2"))
    if(evidence.isNotEmpty())dao.addEvidence(evidence)
    dao.capReports()
   }
   onUpdate(summary.text)
   return id
  }
  val docs=dao.candidates(fromAt,toAt,allSources,allowed,filters.languages.toList(),pattern,alternate)
  val coverage=dao.coverage(fromAt,toAt,allSources,allowed,filters.languages.toList(),pattern,alternate)
  val filterNote="filters: sources=${if(filters.sourceIds.isEmpty())"all" else filters.sourceIds.size}, publishers=${if(filters.publishers.isEmpty())"all" else filters.publishers.joinToString(",")}, topic=${filters.topic.ifBlank{"none"}}, languages=${filters.languages.sorted().joinToString(",")}, from=${java.time.Instant.ofEpochMilli(fromAt)}, to=${java.time.Instant.ofEpochMilli(toAt)}"
  require(docs.isNotEmpty()){"No indexed articles match ($filterNote). Check research switches, date window, selected languages and literal topic; unknown-language items need Unknown enabled."}
  val topic=Algorithms.latestTopic(question)
  val selection=withContext(Dispatchers.Default){LocalRetriever.rank(if(filters.topic.isBlank())question else "$question ${filters.topic}",docs)}
  val ranked=selection.ranked
  require(ranked.isNotEmpty()){selection.limitation.ifBlank{"No confident local lexical match in ${docs.map{it.articleId}.distinct().size} candidate articles ($filterNote). Try specific terms or broaden the filters."}}
  val key=settings.apiKey();require(key.isNotBlank()){"Save your API key in Settings to generate an answer; local filtering requires no key."}
  val id=UUID.randomUUID().toString()
  val bodies=ranked.take(minOf(100,c.topK.coerceIn(1,24)*6)).map{docs[it].articleId}.distinct().mapNotNull{articleId->dao.article(articleId)?.body?.let{articleId to it}}.toMap()
  val plan=EvidencePlanner.plan(docs,ranked,bodies,c,question,id,now,if(topic!=null)1400 else 6000)
  val evidence=plan.evidence
  require(evidence.isNotEmpty()){ "No evidence fits the context budget. Increase context tokens or reduce reserved output." }
  val knownPublishers=docs.mapNotNull{LocalRetriever.publisher(it.sourceUrl)}.distinct().size
  val scope="Indexed coverage: ${coverage.articleCount} articles from ${coverage.sourceCount} feeds ($knownPublishers known publisher hosts in candidates). Newest indexed item: ${coverage.newestAt?.let{java.time.Instant.ofEpochMilli(it)}?:"unknown"}; last successful feed refresh: ${coverage.lastRefresh?.takeIf{it>0}?.let{java.time.Instant.ofEpochMilli(it)}?:"never"}. Undated items use first-seen time, not a verified publication date."
  val selected=evidence.mapIndexed{index,item->
   val docIndex=ranked.firstOrNull{docs[it].url==item.url&&docs[it].title==item.title}
   "[${index+1}] ${LocalRetriever.publisher(docIndex?.let{docs[it].sourceUrl}?:"")?:item.source}: ${item.title.take(50)} (${docIndex?.let{selection.reasons[it]}?:"recency"})"
  }.joinToString("; ")
  val selectionNote="$scope | $filterNote | candidates: ${docs.size} chunks / ${docs.map{it.articleId}.distinct().size} articles; matched: ${selection.matchedArticles} articles (local BM25, source tags are hints; no query embeddings); selected: $selected | evidence: ${evidence.size}, context-limited: ${plan.budgetLimited}, excerpts/clipped: ${plan.clipped}, estimated input tokens (character heuristic): ${plan.estimatedInputTokens}/${plan.contextLimit}, output limit: ${plan.outputLimit}"
  db.withTransaction{dao.addReport(ReportEntity(id,question,requestedModel=(listOf(c.model)+c.fallbacks.split(',').filter{it.isNotBlank()}).joinToString(",")));dao.addEvidence(evidence)}
  val text=StringBuilder();var model="";var finishReason="";var isDone=false;var streamError="";val retrievalNote=""
  var pTokens=0;var cTokens=0;var tTokens=0;var rTokens=0
  var status="PARTIAL"
  try{coroutineScope{
  val ticker=launch{var last="";var savedAt=0L;while(isActive){delay(50);val value=text.toString();if(value!=last){onUpdate(value);last=value};if(System.currentTimeMillis()-savedAt>1000){dao.finish(id,value,"RUNNING",model,listOf(retrievalNote,selectionNote).filter{it.isNotBlank()}.joinToString(" | "));savedAt=System.currentTimeMillis()}}}
   try{
    ai.stream(c,key,if(topic!=null) "$question\n\n$scope" else question,evidence).collect{event->
     if(event.error.isNotBlank())streamError=event.error
     if(event.text.isNotEmpty())text.append(event.text)
     if(event.model.isNotBlank())model=event.model
     if(event.finishReason.isNotBlank())finishReason=event.finishReason
     if(event.done)isDone=true
     if(event.promptTokens>0)pTokens=event.promptTokens
     if(event.completionTokens>0)cTokens=event.completionTokens
     if(event.totalTokens>0)tTokens=event.totalTokens
     if(event.reasoningTokens>0)rTokens=event.reasoningTokens
    }
   }finally{ticker.cancelAndJoin()}
  }
  status=generationStatus(text.toString(),finishReason,isDone,streamError)
  if(status=="PARTIAL"&&streamError.isBlank()&&finishReason!="length")streamError="Stream ended without terminal confirmation"
  if(status=="FAILED"&&streamError.isBlank()&&finishReason!="length")streamError="Empty response from provider"
  }
  catch(e:CancellationException){streamError="Cancelled by user";status="PARTIAL";throw e}
  catch(e:Exception){streamError=e.message?:"Generation failed";status=if(text.isEmpty())"FAILED" else "PARTIAL"}
  finally{
   val diagDetails=listOfNotNull(
    retrievalNote.ifBlank{null},
    selectionNote,
    if(finishReason=="length"&&text.isBlank()) "Output limit reached without an answer (reasoning tokens: $rTokens). Increase output tokens in Settings or choose a less reasoning-intensive model." else null,
    streamError.ifBlank{null},
    "finish_reason: ${finishReason.ifBlank{"none"}}",
    if(tTokens>0)"tokens: $tTokens (prompt: $pTokens, completion: $cTokens${if(rTokens>0) ", reasoning: $rTokens" else ""})" else null
   ).joinToString(" | ")
   withContext(NonCancellable){dao.finish(id,text.toString(),status,model,diagDetails);dao.capReports()}
   onUpdate(text.toString())
  }
  return id
 }
}
