package com.example.mobileosint
import android.database.sqlite.SQLiteConstraintException
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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

data class ResearchFilters(val fromAt:Long?=null,val toAt:Long?=null,val sourceIds:Set<String> = emptySet(),val publishers:Set<String> = emptySet(),val topic:String="",val languages:Set<String> = setOf("pt","en","und"),val trendMinutes:Int=60)
enum class ResearchLanguageMode(val label:String,val languageCodes:Set<String>,val speechLocale:String,val speechUiLabel:String){
 PORTUGUESE("Portuguese",setOf("pt"),"pt-BR","Portuguese language intput"),
 ENGLISH("English",setOf("en","und"),"en-US","English language intput"),
 BOTH("Both",setOf("pt","en","und"),"en","English language intput");
 companion object{fun fromCodes(codes:Set<String>)=entries.firstOrNull{it.languageCodes==codes}?:BOTH}
}
data class TrendSupport(val title:String,val url:String,val publisher:String,val publishedAt:Long?,val firstSeenAt:Long,val excerpt:String)
data class TrendKeyword(val key:String,val label:String,val currentArticles:Int,val currentPublishers:Int,val previousArticles:Int,val previousPublishers:Int,val status:String,val supports:List<TrendSupport>,val variations:List<String>?=null,val metadata:String?=null)
data class KeywordReport(val kind:String="keyword-trends-v2",val start:Long,val end:Long,val previousStart:Long,val currentArticles:Int,val currentPublishers:Int,val currentUndated:Int,val previousArticles:Int,val previousPublishers:Int,val previousUndated:Int,val staleFeeds:Int,val sourceCount:Int,val lastRefresh:Long?,val baselineSparse:Boolean,val filters:String,val keywords:List<TrendKeyword>)
data class TrendSummary(val text:String,val diagnostics:String,val supporting:List<TrendArticle>,val report:KeywordReport)
data class TrendScoreDiagnostic(val articleFrequency:Int,val articleCount:Int,val idf:Double,val coverageBonus:Double,val phraseWeight:Double,val acronymWeight:Double,val total:Double)
data class RelatedTrendPhraseDiagnostic(val key:String,val displayTerm:String?,val supportArticleIds:List<String>,val previousSupportArticleIds:List<String>,val score:TrendScoreDiagnostic,val candidateEligible:Boolean,val candidateExclusionReason:String?,val candidateRank:Int?,val selectionReason:String?,val selectedRank:Int?,val finalCardRank:Int?,val variationOf:String?)
data class StandaloneTrendDiagnostic(val key:String,val displayTerm:String?,val candidateGenerated:Boolean,val candidateExclusionReason:String?,val supportArticleIds:List<String>,val previousSupportArticleIds:List<String>,val score:TrendScoreDiagnostic?,val candidateRank:Int?,val selectionReason:String?,val selectedRank:Int?,val finalCardRank:Int?,val selectionOutcome:String,val removedByStandaloneDuplicates:Boolean,val duplicatePhraseKeys:List<String>,val relatedPhrases:List<RelatedTrendPhraseDiagnostic>)
data class TrendInputArticleDiagnostic(val articleId:String,val effectiveAt:Long,val publishedAt:Long?,val firstSeenAt:Long,val sourceId:String,val researchEligible:Boolean)
data class TrendInputProvenance(val start:Long,val end:Long,val previousStart:Long,val baselineComplete:Boolean,val languages:List<String>,val allSources:Boolean,val selectedSourceIds:List<String>,val sourceFilterIds:List<String>,val publisherFilters:List<String>,val topicFilter:String,val topicPattern:String,val alternateTopicPattern:String,val researchEligibleSourceScope:Boolean,val current:List<TrendInputArticleDiagnostic>,val preceding:List<TrendInputArticleDiagnostic>)
data class TrendRunDiagnostic(val provenance:TrendInputProvenance,val terms:List<StandaloneTrendDiagnostic>)
object KeywordReportCodec {
 private val gson=Gson()
 fun encode(report:KeywordReport):String=gson.toJson(report)
 fun decode(text:String):KeywordReport?=if(!text.startsWith("{"))null else runCatching{
  if(JsonParser.parseString(text).asJsonObject.get("kind")?.asString=="keyword-trends-v2"){
   val report=gson.fromJson(text,KeywordReport::class.java)
   report.copy(keywords=report.keywords.map{it.copy(variations=it.variations.orEmpty(),metadata=it.metadata?:KeywordTrends.variationMetadata(it.variations.orEmpty()))})
  }else null
 }.getOrNull()
}

object KeywordTrends {
 /** Optional debug hook for inspecting the real trend inputs. Null by default; emits IDs and scores, never article text. */
 @Volatile var standaloneDiagnosticSink:((List<StandaloneTrendDiagnostic>)->Unit)?=null
 internal fun combinedScore(articleCount:Int,distinctHostCount:Int,maxArticleCount:Int,maxHostCount:Int,wordCount:Int=1,totalArticles:Int=0):Double{
  val volumeScore=if(maxArticleCount>0)100.0*articleCount/maxArticleCount else 0.0
  val diversityScore=if(maxHostCount>0)100.0*distinctHostCount/maxHostCount else 0.0
  val baseScore=(volumeScore+diversityScore)/2.0
  val multiWordBoost = when {
   wordCount >= 4 -> 2.2  // Multi-word phrase boost
   wordCount == 3 -> 1.8  // Progressive triplet boost
   wordCount == 2 -> 1.3  // Standard doublet weight
   else -> 1.0
  }

  val saturationPenalty=if(wordCount==1&&totalArticles>0){
   val penetrationRate=articleCount.toDouble()/totalArticles
   if(penetrationRate>0.05)0.5 else 1.0
  }else{
   1.0
  }
  return baseScore*multiWordBoost*saturationPenalty
 }
 internal fun combinedScore(articleCount:Int,distinctHostCount:Int,maxArticleCount:Int,maxHostCount:Int,term:String,totalArticles:Int=0):Double{
  val wordCount=term.trim().split(Regex("\\s+")).filter{it.isNotBlank()}.size.coerceAtLeast(1)
  return combinedScore(articleCount,distinctHostCount,maxArticleCount,maxHostCount,wordCount,totalArticles)
 }
 internal fun inputProvenance(start:Long,end:Long,previousStart:Long,baselineComplete:Boolean,languages:List<String>,allSources:Boolean,selectedSourceIds:List<String>,filters:ResearchFilters,topicPattern:String,alternateTopicPattern:String,current:List<TrendArticle>,previous:List<TrendArticle>)=TrendInputProvenance(
  start,end,previousStart,baselineComplete,languages.distinct().sorted(),allSources,selectedSourceIds.distinct().sorted(),filters.sourceIds.sorted(),filters.publishers.sorted(),filters.topic,topicPattern,alternateTopicPattern,true,
  current.map{TrendInputArticleDiagnostic(it.id,it.effectiveAt,it.publishedAt,it.firstSeenAt,it.sourceId,true)},
  previous.map{TrendInputArticleDiagnostic(it.id,it.effectiveAt,it.publishedAt,it.firstSeenAt,it.sourceId,true)}
 )
 data class Window(val start:Long,val end:Long,val precedingStart:Long,val baselineComplete:Boolean)
 fun window(now:Long,retentionStart:Long,filters:ResearchFilters):Window{
  require(filters.trendMinutes in 15..1440){"Trend window must be 15-1440 minutes."}
  val end=minOf(now,filters.toAt?:now)
  val start=maxOf(end-filters.trendMinutes*60_000L,filters.fromAt?:Long.MIN_VALUE,retentionStart)
  require(start<end){"No recent time window remains within the selected dates and retention."}
  val precedingStart=start-(end-start)
  return Window(start,end,precedingStart,precedingStart>=retentionStart)
 }
 private val englishStop=("a an and are as at be been before but by can did do does for from had has have he he's her hers him his how i if in into is it its may me my not of on one or other our out over per s she that their the there these this those to us was we week weeks were what when where which while who will with would year years you your "+
  "about after also all any each even first here just more most much now only said say she some than them then they through too under up very we were why yet could should would might likely meanwhile reports reported says urged urges").split(Regex("\\s+")).filter{it.isNotBlank()}.map(::key).toSet()
 private val portugueseStop=("a ano anos as ao aos apos ate com como da das de deixa dela dele depois deve devem do dos e em enquanto entre essa esse esta estao estava estavam este eu foi foram ha isso ja mais mas me meu minha na nas nao nesta neste no nos o os ou para pela pelo por porque que sao se sem seu sua seus suas ser sera serao sendo sobre tambem tem ter tu um uma uns umas presidente "+
  "ainda alguns algumas antes assim ate cada contra debaixo deste destes destas desde dia dias durante eles elas ela ele era eram essa essas esse esses estas estes eu hoje inicio mesmo muito muita muitos muitas nossa nossas nosso nossos onde pela pelas pelo pelos pode podem poderia podera pois qual quando quem se serao sido sob suas todo toda todos todas vai vao voce voces").split(Regex("\\s+")).filter{it.isNotBlank()}.map(::key).toSet()
 private val noise=("article articles click clicks comment comments continue edition editions feed feeds follower followers following headline headlines homepage menu newsletter newsletters post posts privacy reply replies response responses rights reserved share shares shared sharing source sources story stories subscribe subscriber subscribers subscription subscriptions submitted terms copyright photo photos photograph photographs gallery galleries picture pictures graphic graphics interactive transcript transcripts image images video videos link links audio podcast podcasts " +
         "artigo artigos assine assinatura assinaturas assinar comentario comentarios comentar confira curtir curtida curtidas fonte fontes hoje inscreva-se jornal jornais leia noticia noticias novo nova novos novas pagina paginas publicidade responder resposta respostas saiba segundo seguidores seguir veja tambem getty images clique aqui " +
         "manha madrugada morning mornings afternoon afternoons evening evenings night nights midnight dawn hora horas minuto minutos segundo segundos hour hours minute minutes second seconds instante instantes momento momentos instant instants moment moments " +
         "janeiro fevereiro marco abril maio junho julho agosto setembro outubro novembro dezembro january february march april may june july august september october november december " +
         "segunda-feira terca-feira quarta-feira quinta-feira sexta-feira sabado domingo monday tuesday wednesday thursday friday saturday sunday " +
         "zero um uma dois duas tres quatro cinco seis sete oito nove dez cento cem mil milhao milhoes bilhao bilhoes one two three four five six seven eight nine ten hundred thousand million billion first second third fourth fifth " +
         "galeria galerias foto fotos imagem imagens video videos link links audio podcast podcasts credito creditos autoria parceria parcerias " +
         "national international regional general public private exclusive report newsroom agency text texts image images picture pictures video videos link links audio podcast podcasts credit credits authorship partnership " +
         "galeria de fotos leia mais saiba mais veja mais veja tambem read more privacy policy terms of service terms and conditions photo gallery photo credit image credit all rights reserved follow us click here continue reading related articles related stories read online full coverage click to expand ").split(Regex("\\s+")).filter{it.isNotBlank()}.map(::key).toSet()
 private val boilerplate=Regex("(?i)\\b(?:url|ap news|europe live|breaking news|latest news|read more|continue reading|click\\s+here|clique\\s+aqui|clique|aqui|click|here|share this|skip to content|leia mais|saiba mais|veja tamb[eé]m|leave a comment|add a comment|no comments|post a comment)\\b")
 private val markup=Regex("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)>|<[^>]+>|&(?:#\\d+|#x[0-9a-f]+|[a-z]+);")
 private val urls=Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>]+")
 private val feedPrefix=Regex("(?i)^\\s*arxiv:\\S+\\s+announce type:\\s*\\w+\\s+abstract:\\s*")
 // U+FFFD is included so a decoder replacement character bridges a word instead of splitting it
 // into fragments such as "bado" ("s\uFFFDbado") or "nculos" ("v\uFFFDnculos").
 private val word=Regex("[\\p{L}\\p{N}\\p{Sc}\\uFFFD]+(?:-[\\p{L}\\p{N}\\p{Sc}\\uFFFD]+)*")
 /** Standalone-uninformative terms for every language mode: filtered as single tokens, still eligible inside 2-3 word phrases. */
 private val phraseOnlyTerms=setOf("access", "according", "administration", "against", "ahead", "amid", "announced", "appeared", "attacks", "authorities", "back", "being", "between", "big", "brazil", "brasil", "campaign", "chief", "children", "city", "company", "country", "court", "data", "day", "days", "deal", "development", "dois", "down", "during", "election", "elementary", "end", "federal", "fire", "former", "future", "game", "global", "government", "group", "hit", "home", "including", "killed", "last", "leaders", "least", "life", "made", "major", "make", "man", "market", "media", "midterm", "military", "million", "month", "near", "next", "night", "off", "officials", "oil", "party", "people", "pessoas", "point", "points", "police", "political", "president", "press", "prices", "rio", "school", "season", "secretary", "series", "set", "shooter", "shooting", "show", "since", "state", "still", "support", "take", "three", "time", "times", "told", "two", "vida", "voters", "war", "win", "world").map(::key).toSet()
 /** Portuguese equivalents of the background terms above, so both languages are filtered equivalently; standalone use only. */
 private val portuguesePhraseOnlyTerms=phraseOnlyTerms+setOf("acesso","acordo","acidente","agora","anuncio","anuncios","ataque","ataques","autoridade","autoridades","assunto","assuntos","base","bases","campanha","carro","carros","caso","casos","central","cidade","cidades","coisa","coisas","conta","contas","crianca","criancas","dado","dados","desenvolvimento","economia","eleicao","eleicoes","eleitor","eleitores","empresa","empresas","escola","estado","estados","fato","fatos","forma","formas","frente","frentes","futuro","gente","governo","grande","grandes","grupo","guerra","homem","imprensa","incendio","incendios","interior","lado","lados","lider","lideres","maior","maiores","mercado","menor","menores","mes","meses","meio","midia","militar","militares","milhao","milhoes","momento","momentos","mundo","noite","nome","nomes","numero","numeros","ontem","pais","paises","parte","partes","partido","petroleo","policia","ponto","pontos","prazo","prazos","preco","precos","primeira","primeiro","secretario","semana","semanas","temporada","tempo","tipo","tipos","tribunal","ultima","ultimas","ultimo","ultimos","valor","valores","vez","vezes","vitoria","vitorias").map(::key).toSet()
 /** English generic vocabulary that stays available inside phrases but must not become a topic on frequency alone. */
 private val englishPhraseOnlyTerms=phraseOnlyTerms+setOf("part","parts","case","cases","thing","things","way","ways","issue","issues","question","questions","subject","subjects","many","several","various","number","numbers","economy","key","new").map(::key).toSet()
 private val ambiguousPhraseOnlyTerms=englishPhraseOnlyTerms+portuguesePhraseOnlyTerms
 /** A known language uses its own generic vocabulary; an ambiguous one keeps both, like the existing stopword convention. */
 private fun phraseOnly(language:String)=when(language){"pt"->portuguesePhraseOnlyTerms;"en"->englishPhraseOnlyTerms;else->ambiguousPhraseOnlyTerms}
 private fun key(value:String)=Normalizer.normalize(value.trim().lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").replace("\uFFFD","")
 private fun clean(value:String)=value.replace(feedPrefix,"").replace(urls," ").replace(markup," ").replace(boilerplate,".")
 internal fun terms(article:TrendArticle):Map<String,String>{
  val result=linkedMapOf<String,String>()
  val standaloneOnly=phraseOnly(article.language)
  fun allowed(value:String)=allowedToken(value,article.language)
  fun collect(text:String){
   val matches=word.findAll(text).toList()
   matches.forEach{match->
    val token=match.value.trim()
    if(allowed(token)&&key(token) !in standaloneOnly)result.putIfAbsent(key(token),token)
   }
   for(size in 2..minOf(3,matches.size))for(index in 0..matches.size-size){
    val group=matches.subList(index,index+size)
    if(group.zipWithNext().any{(first,second)->text.substring(first.range.last+1,second.range.first).isNotBlank()})continue
    val first=group.first().value;val last=group.last().value
    val named=first.first().isUpperCase()&&last.first().isUpperCase()
    if(!allowed(first)&&!(named&&key(first) in setOf("sao","one","presidente","president"))||
     !allowed(last)&&!(named&&key(last) in setOf("week","weeks")))continue
    if(group.drop(1).dropLast(1).any{key(it.value) in noise||it.value.all(Char::isDigit)})continue
    val phrase=group.joinToString(" "){it.value.trim()}.trim()
    result.putIfAbsent(key(phrase),phrase)
   }
  }
  collect(clean(article.title))
  collect(clean(article.body).take(320))
  return result
 }
 internal fun allowedToken(value:String,language:String):Boolean{
  val token=value.trim()
  // Keep the matcher boundary intact, but never promote an unrecoverable label into a trend card.
  if(token.length<3||token.contains('\uFFFD'))return false
  val normalized=key(token)
  val stops=when(language){"pt"->portugueseStop;"en"->englishStop;else->portugueseStop+englishStop}
  if(normalized in noise||!normalized.any{it.isLetter()})return false
  return normalized !in stops
 }
 internal fun standaloneDuplicates(topKeys:List<String>):Set<String>{
  val phraseWords=topKeys.flatMap{candidate->
   val words=keyWords(candidate)
   if(words.size>1)words else emptyList()
  }.toSet()
  return topKeys.map(::key).filter{candidate->
   val words=keyWords(candidate)
   words.size==1&&candidate in phraseWords
  }.toSet()
 }
 private fun keyWords(value:String)=key(value).split(Regex("\\s+")).filter{it.isNotBlank()}
 private fun meaningfulKeyWords(value:String)=keyWords(value).filter{it.length>=3&&it !in englishStop&&it !in portugueseStop&&it !in noise}
 /** Related candidates group as variations: a shared meaningful word between two phrases, or a curated multilingual synonym pair (labels are kept). */
 internal fun overlappingPhrases(first:String,second:String):Boolean{
  val firstWords=meaningfulKeyWords(first);val secondWords=meaningfulKeyWords(second)
  return firstWords.size>1&&secondWords.size>1&&firstWords.any{it in secondWords}||aliasEquivalent(first,second)
 }
 /** Article-overlap (Jaccard) above which two keyword phrases are the same story and must share one card. */
 internal const val ARTICLE_OVERLAP_THRESHOLD=0.5
 /** Jaccard similarity of two article-id sets: |first ∩ second| / |first ∪ second|; 0.0 when either side has no articles. */
 internal fun jaccardOverlap(first:Collection<String>,second:Collection<String>):Double{
  if(first.isEmpty()||second.isEmpty())return 0.0
  val firstIds=if(first is Set<String>)first else first.toSet()
  val secondIds=if(second is Set<String>)second else second.toSet()
  val union=firstIds.union(secondIds).size
  return if(union==0)0.0 else firstIds.intersect(secondIds).size.toDouble()/union
 }
 /** Card metadata for grouped keyword phrases: the lower-ranked overlapping phrases shown under the main title. */
 internal fun variationMetadata(variations:List<String>):String?=
  variations.takeIf{it.isNotEmpty()}?.joinToString(prefix="Also covers: ",separator=", ")
 /** Multilingual equivalence comes only from the app's curated synonym table, never from string similarity; labels stay untouched. */
 private fun aliasEquivalent(first:String,second:String):Boolean{
  val firstWords=keyWords(first);val secondWords=keyWords(second)
  return firstWords.size==1&&secondWords.size==1&&firstWords.first()!=secondWords.first()&&Algorithms.aliases(firstWords.first()).toSet()==Algorithms.aliases(secondWords.first()).toSet()
 }
 private fun excerpt(article:TrendArticle,term:String):String{
  if(term in terms(article.copy(body="")))return article.title
  val body=clean(article.body).take(320)
  val matches=word.findAll(body).toList()
  val parts=term.trim().split(Regex("\\s+"))
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
   fun analyze(current:List<TrendArticle>,previous:List<TrendArticle>,health:TrendFeedHealth,start:Long,end:Long,filters:String,baselineComplete:Boolean=true,sourceRefresh:Map<String,Long> = emptyMap(),diagnosticCapture:((List<StandaloneTrendDiagnostic>)->Unit)?=null):TrendSummary{
  val recent=current.distinctBy{it.id};val earlier=previous.distinctBy{it.id}
  val currentIndex=index(recent);val previousIndex=index(earlier)
  val undatedRecent=recent.count{it.publishedAt==null};val undatedEarlier=earlier.count{it.publishedAt==null}
  val baselineSparse=!baselineComplete||earlier.size<3||publisherCount(earlier)<2
  val stale=health.staleCount>0
  val coverage="${recent.size} articles / ${publisherCount(recent)} publisher hosts (previous: ${earlier.size} articles / ${publisherCount(earlier)} publisher hosts)"
  val lines=mutableListOf("Keyword coverage in indexed feeds (local, not web-wide)",
   "Current: ${TrendDisplay.clock(start)} to ${TrendDisplay.clock(end)}: ${recent.size} articles, ${publisherCount(recent)} known publisher hosts, $undatedRecent without publication dates.",
   "Previous equal window: ${TrendDisplay.clock(start-(end-start))} to ${TrendDisplay.clock(start)}: ${earlier.size} articles, ${publisherCount(earlier)} known publisher hosts, $undatedEarlier without publication dates.",
   "Source freshness: ${health.staleCount}/${health.sourceCount} feeds not successfully refreshed in the current window; last success: ${health.lastRefresh?.takeIf{it>0}?.let{TrendDisplay.clock(it)}?:"never"}.")
  if(baselineSparse)lines.add("Sparse baseline: too few preceding articles or publishers, or the preceding window extends beyond retained history; no trend can be established.")
  if(stale)lines.add("Stale or unrefreshed feed coverage: apparent increases may reflect ingestion timing, not news activity.")
  val ranked=mutableListOf<Map.Entry<String,List<TrendArticle>>>()
  val repeated=currentIndex.values.any{it.size>=2&&publisherCount(it)>=2}
  fun scoreComponents(entry:Map.Entry<String,List<TrendArticle>>):TrendScoreDiagnostic{
   val documents=recent.size.toDouble();val frequency=entry.value.size.toDouble()
   val idf=ln(1.0+(documents-frequency+0.5)/(frequency+0.5))
   val words=entry.key.split(' ')
   val phraseWeight=if(words.size==1)1.0 else {
    val endpoints=listOf(words.first(),words.last())
    val expected=endpoints.fold(documents){estimate,token->estimate*(currentIndex[token]?.size?:0)/documents}
    1.1+ln((frequency/expected.coerceAtLeast(1.0)).coerceAtLeast(1.0)).coerceAtMost(1.0)
   }
   val coverageBonus=if(documents<5)frequency*0.6 else 0.0
   val acronymWeight=if(entry.key in setOf("us","eu","ia","df"))1.25 else 1.0
   val total=(frequency*idf+coverageBonus)*phraseWeight*acronymWeight
   return TrendScoreDiagnostic(frequency.toInt(),recent.size,idf,coverageBonus,phraseWeight,acronymWeight,total)
  }
  val scoreCache=mutableMapOf<String,TrendScoreDiagnostic>()
  fun components(entry:Map.Entry<String,List<TrendArticle>>)=scoreCache.getOrPut(entry.key){scoreComponents(entry)}
  fun score(entry:Map.Entry<String,List<TrendArticle>>)=components(entry).total
   val candidates=currentIndex.entries.filter{!repeated||it.value.size>=2&&publisherCount(it.value)>=2}
    .sortedWith(compareByDescending<Map.Entry<String,List<TrendArticle>>>(::score).thenByDescending{it.value.size}.thenByDescending{publisherCount(it.value)}.thenByDescending{it.key.split(' ').size}.thenBy{it.key})
  val redundantStandalone=mutableSetOf<String>()
  val standaloneDuplicateReasons=linkedMapOf<String,List<String>>()
  val candidateSelectionReasons=linkedMapOf<String,String>()
  val variationsByParent=linkedMapOf<String,LinkedHashSet<String>>()
  while(true){
   ranked.clear()
   variationsByParent.clear()
   for(candidate in candidates){
     if(candidate.key in redundantStandalone){
      candidateSelectionReasons[candidate.key]="Removed by standaloneDuplicates using ${standaloneDuplicateReasons[candidate.key].orEmpty().joinToString()}"
      continue
     }
     candidateSelectionReasons.remove(candidate.key)
    val candidateIds = candidate.value.map { it.id }.toSet()
    val candidatePrior = previousIndex[candidate.key].orEmpty().map { it.id }.toSet()

    val parent = ranked.firstOrNull { chosen ->
     val memberKeys = listOf(chosen.key) + variationsByParent[chosen.key].orEmpty()
     val textOverlap = memberKeys.any { overlappingPhrases(it, candidate.key) }
     val articleOverlap = memberKeys.any { member ->
      jaccardOverlap(candidateIds, currentIndex[member].orEmpty().map { it.id }) >= ARTICLE_OVERLAP_THRESHOLD
     }

     textOverlap || articleOverlap
    }

    if (parent != null) {
     candidateSelectionReasons[candidate.key] = "Grouped as a variation of ${parent.key} due to text or article overlap"
     variationsByParent.getOrPut(parent.key) { linkedSetOf() }.add(candidate.key)
     continue
    }
    val overlap=ranked.any{chosen->
     val chosenIds=chosen.value.map{it.id}.toSet();val chosenPrior=previousIndex[chosen.key].orEmpty().map{it.id}.toSet()
     chosen.key.split(' ').size>1&&candidate.key.split(Regex("\\s+")).any{it in chosen.key.split(Regex("\\s+"))}&&candidateIds.size>=2&&publisherCount(candidate.value)>=2&&
      jaccardOverlap(candidateIds,chosenIds)>=0.8&&
      jaccardOverlap(candidatePrior,chosenPrior)>=0.8
    }
     if(overlap)candidateSelectionReasons[candidate.key]="Suppressed by existing article-overlap comparison against a selected phrase"
    if(!overlap)ranked.add(candidate)
    if(ranked.size==8)break
   }
   val bracketKeys=ranked.map{it.key}+variationsByParent.values.flatMap{it}
   val newlyRedundant=standaloneDuplicates(bracketKeys)-redundantStandalone
   newlyRedundant.forEach{term->standaloneDuplicateReasons[term]=bracketKeys.filter{phrase->keyWords(phrase).size>1&&term in keyWords(phrase)}}
   if(newlyRedundant.isEmpty())break
   redundantStandalone.addAll(newlyRedundant)
  }
  val supporting=mutableListOf<TrendArticle>()
  val keywords=mutableListOf<TrendKeyword>()
  val keywordLines=mutableListOf<List<String>>()
  val keywordSupporting=mutableListOf<List<TrendArticle>>()
  if(ranked.isEmpty())lines.add("No indexed articles in the current window; no keyword trend can be assessed.")
  val summaryPrefixSize=lines.size
  val strengthArticleCap=candidates.maxOfOrNull{it.value.size}?:0
  val strengthHostCap=candidates.maxOfOrNull{publisherCount(it.value)}?:0
  /** The card title is the grouped phrase with the highest trend score; ties keep the better ranked phrase. */
  fun trendScore(candidateKey:String):Double{
   val candidateArticles=currentIndex[candidateKey].orEmpty()
   return combinedScore(candidateArticles.size,publisherCount(candidateArticles),strengthArticleCap,strengthHostCap,candidateKey,recent.size)
  }
  for((rankedTerm,_) in ranked){
   val groupKeys=listOf(rankedTerm)+variationsByParent[rankedTerm].orEmpty()
   val term=groupKeys.reduceOrNull{best,candidate->if(trendScore(candidate)>trendScore(best))candidate else best}?:rankedTerm
   val variationKeys=groupKeys.filterNot{it==term}
    .sortedWith(compareByDescending<String>{trendScore(it)}.thenByDescending{currentIndex[it].orEmpty().size}.thenByDescending{publisherCount(currentIndex[it].orEmpty())})
   val articles=currentIndex[term].orEmpty()
   val prior=previousIndex[term].orEmpty()
   val currentArticles=(articles+variationKeys.flatMap{currentIndex[it].orEmpty()}).distinctBy{it.id}
   val previousArticles=(prior+variationKeys.flatMap{previousIndex[it].orEmpty()}).distinctBy{it.id}
   val currentPublishers=publisherCount(currentArticles);val previousPublishers=publisherCount(previousArticles)
   val missingDates=currentArticles.any{it.publishedAt==null}||previousArticles.any{it.publishedAt==null}
  val staleRelevant=stale&&(sourceRefresh.isEmpty()||(currentArticles+previousArticles).any{(sourceRefresh[it.sourceId]?:Long.MIN_VALUE)<start})
   val countRise=currentArticles.size>previousArticles.size&&currentPublishers>=previousPublishers
   /** A verified increase needs a larger share of the eligible articles, not only a larger count in a larger window. */
   val shareRise=countRise&&earlier.isNotEmpty()&&currentArticles.size.toLong()*earlier.size>previousArticles.size.toLong()*recent.size
   val status=when{
    baselineSparse||previousArticles.size<2||previousPublishers<2->"sparse baseline; no verified trend"
  staleRelevant->"stale feed coverage; no verified trend (supporting sources stale)"
    missingDates->"publication dates missing; no verified trend"
    currentArticles.size<3||currentPublishers<2->"insufficient current coverage"
   shareRise->if(stale)"increase in indexed coverage among refreshed supporting feeds; wider coverage uncertain" else "increase in indexed coverage"
   countRise->"more articles than the previous window but no larger share of eligible articles; no verified trend"
    else->"no supported increase"
   }
   val label=terms(articles.first())[term]?:term
   val variationLabels=variationKeys.map{key->currentIndex[key]?.firstOrNull()?.let{terms(it)[key]?:key}?:key}
   val cardLineStart=lines.size
   val cardSupportStart=supporting.size
   lines.add("\n$label: ${currentArticles.size} articles / $currentPublishers publishers; previous ${previousArticles.size} articles / $previousPublishers publishers. $status.")

   val metadata=variationMetadata(variationLabels)
   if(metadata!=null)lines.add(metadata)

   keywords.add(TrendKeyword(term,label,currentArticles.size,currentPublishers,previousArticles.size,previousPublishers,status,currentArticles.take(100).map{article->TrendSupport(article.title,article.url,publisher(article)?:"publisher unknown",article.publishedAt,article.firstSeenAt,excerpt(article,term))},variationLabels.takeIf{it.isNotEmpty()},metadata))
  currentArticles.distinctBy{publisher(it)?:"feed:${it.sourceId}"}.take(2).forEach{article->
    lines.add("- ${article.title} (${publisher(article)?:"publisher unknown"}; ${if(article.publishedAt!=null)"published ${TrendDisplay.time(article.publishedAt)}" else "first seen ${TrendDisplay.time(article.firstSeenAt)}, publication unknown"})\n  ${article.url}")
    supporting.add(article)
   }
  keywordLines.add(lines.subList(cardLineStart,lines.size).toList())
  keywordSupporting.add(supporting.subList(cardSupportStart,supporting.size).toList())
  }
  val maxArticleCount=keywords.maxOfOrNull{it.currentArticles}?:0
  val maxHostCount=keywords.maxOfOrNull{it.currentPublishers}?:0
  val totalRunArticles=recent.size
  val sortedIndices=keywords.indices.sortedWith(
   compareByDescending<Int>{combinedScore(keywords[it].currentArticles,keywords[it].currentPublishers,maxArticleCount,maxHostCount,keywords[it].key,totalRunArticles)}
    .thenByDescending{keywords[it].currentArticles}
    .thenByDescending{keywords[it].currentPublishers}
    .thenBy{keywords[it].key}
  )
  val sortedKeywords=sortedIndices.map{keywords[it]}
  sortedKeywords.take(8).forEachIndexed { rank, kw ->
   val wordCount = kw.key.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size.coerceAtLeast(1)
   val score = combinedScore(kw.currentArticles, kw.currentPublishers, maxArticleCount, maxHostCount, kw.key, totalRunArticles)
   val penRate = if (totalRunArticles > 0) kw.currentArticles.toDouble() / totalRunArticles else 0.0
   val boost = when { wordCount >= 3 -> 1.5; wordCount == 2 -> 1.3; else -> 1.0 }
   val penalty = if (wordCount == 1 && penRate > 0.40) 0.5 else 1.0
   Log.d("SpecificityFormula", "TOP_KEYWORDS Rank ${rank + 1}: label='${kw.label}', key='${kw.key}', wordCount=$wordCount, articles=${kw.currentArticles}, publishers=${kw.currentPublishers}, totalArticles=$totalRunArticles, penRate=${"%.4f".format(penRate)}, boost=${boost}, penalty=${penalty}, combinedScore=${"%.4f".format(score)}")
  }
   if(standaloneDiagnosticSink!=null||diagnosticCapture!=null){
   val candidateRanks=candidates.mapIndexed{index,entry->entry.key to index+1}.toMap()
   val selectedParents=ranked.mapIndexed{index,entry->entry.key to index+1}.toMap()
   val finalCardRanks=mutableMapOf<String,Int>()
   sortedIndices.forEachIndexed{index,cardIndex->
    val parent=ranked[cardIndex].key
    finalCardRanks[parent]=index+1
    variationsByParent[parent].orEmpty().forEach{finalCardRanks[it]=index+1}
    finalCardRanks[keywords[cardIndex].key]=index+1
   }
   val variationParents=variationsByParent.flatMap{(parent,variations)->variations.map{it to parent}}.toMap()
   val diagnosticKeys=(listOf("points","semana","silva","ukraine","brasil","help")+ranked.map{it.key}.filter{keyWords(it).size==1}+redundantStandalone).distinct()
   val diagnostics=diagnosticKeys.map{term->
    val singleton=currentIndex[term]
    val singletonEntry=currentIndex.entries.firstOrNull{it.key==term}
    val related=currentIndex.entries.filter{(phrase,_)->keyWords(phrase).size>1&&term in meaningfulKeyWords(phrase)}
     .sortedBy{candidateRanks[it.key]?:Int.MAX_VALUE}.map{entry->
      val parent=variationParents[entry.key]
      RelatedTrendPhraseDiagnostic(entry.key,entry.value.firstOrNull()?.let{terms(it)[entry.key]},entry.value.map{it.id}.distinct(),previousIndex[entry.key].orEmpty().map{it.id}.distinct(),components(entry),entry.key in candidateRanks,
       if(entry.key !in candidateRanks&&repeated&&!(entry.value.size>=2&&publisherCount(entry.value)>=2))"Fails corpus-wide repeated-term support rule (minimum two articles and two publishers)" else null,
       candidateRanks[entry.key],candidateSelectionReasons[entry.key]?:if(entry.key in candidateRanks&&selectedParents[entry.key]==null&&parent==null)"Did not enter the selected top-eight ranked set" else null,selectedParents[entry.key]?:parent?.let{selectedParents[it]},finalCardRanks[entry.key]?:parent?.let{finalCardRanks[it]},parent)
     }
    val candidateRank=candidateRanks[term]
    StandaloneTrendDiagnostic(term,singleton?.firstOrNull()?.let{terms(it)[term]},singleton!=null,
     if(singleton!=null&&candidateRank==null&&repeated&&!(singleton.size>=2&&publisherCount(singleton)>=2))"Fails corpus-wide repeated-term support rule (minimum two articles and two publishers)" else null,
       singleton.orEmpty().map{it.id}.distinct(),previousIndex[term].orEmpty().map{it.id}.distinct(),singletonEntry?.let(::components),candidateRank,candidateSelectionReasons[term]?:if(candidateRank!=null&&selectedParents[term]==null&&term !in redundantStandalone)"Did not enter the selected top-eight ranked set" else null,selectedParents[term],finalCardRanks[term],when{
      singleton==null->"Not generated by extraction"
      candidateRank==null->"Excluded from candidate ranking: ${if(repeated)"fails repeated-term support rule" else "no eligible ranked candidate"}"
      term in redundantStandalone->"Removed by standaloneDuplicates using ${standaloneDuplicateReasons[term].orEmpty().joinToString() }"
      selectedParents[term]==null->"Ranked candidate did not enter the selected top-eight set"
      else->"Selected as a standalone card"
     },term in redundantStandalone,
     standaloneDuplicateReasons[term].orEmpty(),related)
   }
    standaloneDiagnosticSink?.let{sink->runCatching{sink(diagnostics)}}
    diagnosticCapture?.let{capture->runCatching{capture(diagnostics)}}
   }
  val sortedLines=lines.take(summaryPrefixSize)+sortedIndices.flatMap{keywordLines[it]}
  val sortedSupporting=sortedIndices.flatMap{keywordSupporting[it]}.distinctBy{it.id}
  keywords.clear()
  keywords.addAll(sortedKeywords)
  lines.clear()
  lines.addAll(sortedLines)
  supporting.clear()
  supporting.addAll(sortedSupporting)
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
 internal fun effectiveLimit(limit:Int,size:Int):Int{
  val envLimit=System.getenv("MAX_CONCURRENT_FEED_FETCHES")?.toIntOrNull() ?: System.getProperty("MAX_CONCURRENT_FEED_FETCHES")?.toIntOrNull()
  val candidate=if(envLimit!=null&&envLimit>0) envLimit else limit
  return when{
   candidate>0 -> candidate
   size>0 -> size
   else -> 1
  }
 }
 suspend fun <T,R> run(items:List<T>,limit:Int,fetch:suspend (T)->R,apply:suspend (T,Result<R>)->Unit) = coroutineScope {
  if(items.isEmpty())return@coroutineScope
  val batchSize=effectiveLimit(limit,items.size)
  for(batch in items.chunked(batchSize)){
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
  val envLimit=System.getenv("MAX_CONCURRENT_FEED_FETCHES")?.toIntOrNull() ?: System.getProperty("MAX_CONCURRENT_FEED_FETCHES")?.toIntOrNull()
  val batchSize=if(envLimit!=null&&envLimit>0) envLimit else 3
  coroutineScope {
   for(batch in pending.chunked(batchSize)) {
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
class ResearchService(val db:AppDatabase,val settings:Settings,private val rss:FeedProvider=RssProvider()){
 val dao=db.dao();private val embed=EmbeddingProvider();private val ai:AiProvider=OpenRouterProvider();private val vectors:VectorStore=RoomFlatVectorStore()
 private val syncLock=Mutex()
 val progress=MutableStateFlow("Ready")
 val ready=CompletableDeferred<Unit>()
 suspend fun initialize(){try{dao.recover();cleanup();backfillLanguages()}finally{ready.complete(Unit)}}
 suspend fun backfillLanguages(){
  val updates=withContext(Dispatchers.Default){dao.unclassifiedArticles().mapNotNull{article->Algorithms.detectLanguage(article.title+" "+article.body).takeIf{it!="und"}?.let{article.id to it}}}
  db.withTransaction{for((id,language) in updates)dao.setArticleLanguage(id,language)}
 }
 suspend fun addSource(url:String){ready.await();val clean=url.trim();require(clean.startsWith("https://")){"Use an HTTPS RSS/Atom URL"};require(dao.hasSourceUrl(clean)==0){"Feed already exists"}
  val feed=withContext(Dispatchers.IO){rss.fetch(clean)}
  db.withTransaction{try{dao.addSource(SourceEntity(UUID.randomUUID().toString(),clean,feed.title))}catch(e:SQLiteConstraintException){throw IllegalArgumentException("Feed already exists",e)}};progress.value="Feed added. Tap Sync."
 }
 suspend fun importFeeds(input:String,onProgress:(FeedImportState)->Unit):FeedImportState {
  ready.await()
  return FeedImporter.run(input,dao.sourceUrls().toSet(),{url->
   if(dao.hasSourceUrl(url)>0) false
   else try{addSource(url);true}catch(e:CancellationException){throw e}catch(e:Exception){
    if(dao.hasSourceUrl(url)>0) false else throw e
   }
  },onProgress)
 }
 suspend fun toggle(id:String,fetch:Boolean?=null,research:Boolean?=null){db.withTransaction{val s=dao.source(id)?:return@withTransaction;dao.updateSource(s.copy(enabled=fetch?:s.enabled,research=research?:s.research,revision=s.revision+1))}}
 suspend fun setTags(id:String,value:String){require(value.length<=200&&'\n' !in value){"Tags must be one comma-separated line (up to 200 characters)"};db.withTransaction{val s=dao.source(id)?:return@withTransaction;dao.updateSource(s.copy(tags=value.split(',').map{it.trim()}.filter{it.isNotBlank()}.distinct().joinToString(", ")))}}
 suspend fun setAllFeedsEnabled(enabled:Boolean){ready.await();db.withTransaction{dao.setAllFetchEnabled(enabled)}}
 suspend fun deleteSource(id:String){db.withTransaction{dao.deleteSource(id)}}
 data class CleanupResult(val expiredCount:Int=0,val prunedCount:Int=0)

suspend fun cleanup():CleanupResult{
 val c=settings.config()
 var expired=0; var pruned=0
 db.withTransaction{
  expired=dao.expire(Algorithms.cutoff(System.currentTimeMillis(),c.days))
  pruned=dao.cap(c.articleCap)
  dao.capReports()
  dao.capSeen()
  dao.capSyncRuns()
 }
 return CleanupResult(expired,pruned)
}
 private fun emitSyncTelemetry(run:SyncRunEntity,logs:List<FeedSyncLogEntity>){
  val summaryLine="[SYNC_RUN] status=${run.status}, duration=${run.durationMs}ms, feeds=${run.totalFeeds} (${run.successfulFeeds} ok, ${run.failedFeeds} failed), items: +${run.itemsAdded} added, ${run.itemsUpdated} updated, ${run.itemsExpired} expired, ${run.itemsPruned} pruned"
  val jsonPayload=JSONObject().apply{
   put("telemetry_type","sync_run")
   put("run_id",run.id)
   put("timestamp",run.timestamp)
   put("duration_ms",run.durationMs)
   put("total_feeds",run.totalFeeds)
   put("successful_feeds",run.successfulFeeds)
   put("failed_feeds",run.failedFeeds)
   put("items_added",run.itemsAdded)
   put("items_updated",run.itemsUpdated)
   put("items_expired",run.itemsExpired)
   put("items_pruned",run.itemsPruned)
   put("status",run.status)
   put("feeds",JSONArray(logs.map{log->
    JSONObject().apply{
     put("source_id",log.sourceId)
     put("title",log.sourceTitle)
     put("url",log.sourceUrl)
     put("status",log.status)
     put("parsed",log.itemsParsed)
     put("added",log.itemsAdded)
     put("updated",log.itemsUpdated)
     put("expired",log.itemsExpired)
     put("unchanged",log.itemsUnchanged)
     put("error",log.errorMessage)
     put("duration_ms",log.durationMs)
    }
   }))
  }
  Log.i("MobileOsintSyncTelemetry","$summaryLine\n$jsonPayload")
 }
 suspend fun sync():Boolean=syncLock.withLock{
  ready.await();val c=settings.config();val initialCleanup=cleanup();val active=dao.active();var successes=0;var completed=0;var failures=0
  val runId=UUID.randomUUID().toString()
  val startedNano=System.nanoTime()
  val runTimestamp=System.currentTimeMillis()
  val feedLogs=java.util.Collections.synchronizedList(mutableListOf<FeedSyncLogEntity>())
  try{
   RefreshRunner.run(active,c.refreshConcurrency,{source->withContext(Dispatchers.IO){rss.fetch(source.url)}}){source,result->
    val feedStartNano=System.nanoTime()
    val feedStartMs=System.currentTimeMillis()
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
      // The decoder already repaired every item present in this feed. Older cached rows that still
      // carry U+FFFD are lossy and cannot be recovered from the database, so drop them rather than
      // letting diamonds leak into titles, excerpts or trend labels.
      val corruptRemoved=if(parsedCount>0)dao.deleteCorruptArticles(source.id) else 0
      if(corruptRemoved>0)Log.d("MobileOsintDiag","Source ${source.id} dropped $corruptRemoved stale corrupt article(s)")
      val statusStr=when{
       parsedCount==0->"EMPTY_FEED: 0 items parsed"
       insertedCount>0||updatedCount>0->"SUCCESS: $insertedCount new, $updatedCount updated ($parsedCount parsed)"
       expiredCount==parsedCount->"ALL_EXPIRED: $parsedCount parsed, all older than ${c.days}d retention"
       else->"UNCHANGED: $parsedCount parsed, 0 new items"
      }
      Log.d("MobileOsintDiag","Source ${source.id} status: $statusStr (parsed=$parsedCount, expired=$expiredCount, unchanged=$unchangedCount, inserted=$insertedCount, updated=$updatedCount)")
      val isOk=insertedCount>0||updatedCount>0||unchangedCount>0
      dao.updateSource(current.copy(lastAttempt=now,lastSuccess=if(isOk) now else current.lastSuccess,status=(statusStr+if(clippedCount>0)"; $clippedCount clipped" else ""),failures=if(isOk) 0 else current.failures+1));dao.cap(c.articleCap);dao.capSeen()
     }
     val isOk=insertedCount>0||updatedCount>0||unchangedCount>0
     if(isOk) successes++
     val feedDurationMs=(System.nanoTime()-feedStartNano)/1_000_000
     val shortStatus=when{
      parsedCount==0->"EMPTY_FEED"
      insertedCount>0||updatedCount>0->"SUCCESS"
      expiredCount==parsedCount->"ALL_EXPIRED"
      else->"UNCHANGED"
     }
     feedLogs.add(FeedSyncLogEntity(UUID.randomUUID().toString(),runId,source.id,source.title,source.url,feedStartMs,shortStatus,parsedCount,insertedCount,updatedCount,expiredCount,unchangedCount,if(clippedCount>0)"$clippedCount items clipped" else "",feedDurationMs))
    }catch(e:CancellationException){throw e}catch(e:Exception){failures++
     Log.e("MobileOsintDiag","Sync failed for ${source.id}",e)
     val feedDurationMs=(System.nanoTime()-feedStartNano)/1_000_000
     val errStatus="${e.javaClass.simpleName}: ${e.message?:"Fetch failed"}".take(200)
     db.withTransaction{
      val s=dao.source(source.id)
      if(s!=null&&sourceStillCurrent(s,source)){
       dao.updateSource(s.copy(lastAttempt=System.currentTimeMillis(),status=errStatus,failures=s.failures+1))
      }
     }
     feedLogs.add(FeedSyncLogEntity(UUID.randomUUID().toString(),runId,source.id,source.title,source.url,feedStartMs,"FAILED",0,0,0,0,0,errStatus,feedDurationMs))
    }finally{completed++;progress.value="Refreshed $completed/${active.size} feeds ($failures failed)"}
   }
   if(c.embeddings){val key=settings.apiKey();if(key.isNotBlank()){
    progress.value="Indexing up to 100 pending chunks"
    try{for(batch in dao.pending(Algorithms.cutoff(System.currentTimeMillis(),c.days),c.embeddingModel).chunked(16)){
     val vs=embed.embed(key,c.embeddingModel,batch.map{it.text})
     db.withTransaction{batch.forEachIndexed{i,ch->dao.vector(ch.id,Algorithms.encode(vs[i]),c.embeddingModel,Algorithms.cutoff(System.currentTimeMillis(),c.days))}}
    }}catch(e:CancellationException){throw e}catch(e:Exception){progress.value="Feeds synced; embedding error: ${e.message}"}
   }}
   val postCleanup=cleanup()
   val totalAdded=feedLogs.sumOf{it.itemsAdded}
   val totalUpdated=feedLogs.sumOf{it.itemsUpdated}
   val totalExpired=feedLogs.sumOf{it.itemsExpired}+initialCleanup.expiredCount+postCleanup.expiredCount
   val totalPruned=initialCleanup.prunedCount+postCleanup.prunedCount
   val runDurationMs=(System.nanoTime()-startedNano)/1_000_000
   val runStatus=when{
    active.isEmpty()->"SUCCESS"
    failures==0->"SUCCESS"
    successes>0->"PARTIAL"
    else->"FAILED"
   }
   val syncRun=SyncRunEntity(runId,runTimestamp,runDurationMs,active.size,successes,failures,totalAdded,totalUpdated,totalExpired,totalPruned,runStatus)
   runCatching{
    db.withTransaction{
     dao.addSyncRun(syncRun)
     if(feedLogs.isNotEmpty())dao.addFeedSyncLogs(feedLogs)
     dao.capSyncRuns()
    }
   }.onFailure{Log.e("MobileOsintDiag","Failed to persist sync run history",it)}
   runCatching{emitSyncTelemetry(syncRun,feedLogs)}.onFailure{Log.e("MobileOsintDiag","Failed to emit sync telemetry",it)}
   progress.value="Sync finished: $successes/${active.size} feeds, $failures failed, +$totalAdded added, $totalPruned pruned in $runDurationMs ms"
   return successes>0||active.isEmpty()
  }finally{if(progress.value.startsWith("Refreshed")||progress.value.startsWith("Indexing"))progress.value="Sync stopped"}
 }
  suspend fun research(question:String,onUpdate:(String)->Unit,filters:ResearchFilters=ResearchFilters(),trendDiagnosticCapture:((TrendRunDiagnostic)->Unit)?=null):String{
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
   val provenance=trendDiagnosticCapture?.let{KeywordTrends.inputProvenance(start,end,precedingStart,baselineComplete,languages,allSources,allowed,filters,pattern,alternate,recent,preceding)}
   var capturedTerms:List<StandaloneTrendDiagnostic>?=null
   val perRunCapture:((List<StandaloneTrendDiagnostic>)->Unit)?=trendDiagnosticCapture?.let{{terms->capturedTerms=terms}}
   val summary=withContext(Dispatchers.Default){KeywordTrends.analyze(recent,preceding,health,start,end,filterNote,baselineComplete,refresh,perRunCapture)}
   if(trendDiagnosticCapture!=null&&provenance!=null&&capturedTerms!=null)runCatching{trendDiagnosticCapture(TrendRunDiagnostic(provenance,capturedTerms!!))}
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
  val filterNote="filters: sources=${if(filters.sourceIds.isEmpty())"all" else filters.sourceIds.size}, publishers=${if(filters.publishers.isEmpty())"all" else filters.publishers.joinToString(",")}, topic=${filters.topic.ifBlank{"none"}}, languages=${filters.languages.sorted().joinToString(",")}, from=${if(fromAt>0) TrendDisplay.clock(fromAt) else "start"}, to=${TrendDisplay.clock(toAt)}"
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
  val scope="Indexed coverage: ${coverage.articleCount} articles from ${coverage.sourceCount} feeds ($knownPublishers known publisher hosts in candidates). Newest indexed item: ${coverage.newestAt?.let{TrendDisplay.time(it)}?:"unknown"}; last successful feed refresh: ${coverage.lastRefresh?.takeIf{it>0}?.let{TrendDisplay.time(it)}?:"never"}. Undated items use first-seen time, not a verified publication date."
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
