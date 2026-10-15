package com.example.mobileosint

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlin.system.measureTimeMillis

class AlgorithmsTest {
 @Test fun keywordTrendIntentPrecedesOrdinaryTopicAndLatestNews(){
  assertTrue(Algorithms.keywordTrendIntent("what are the keywords trending"))
  assertTrue(Algorithms.keywordTrendIntent("Quais palavras-chave estao em alta?"))
  assertTrue(Algorithms.keywordTrendIntent("termos mais citados"))
  assertFalse(Algorithms.keywordTrendIntent("latest news about climate"))
  assertFalse(Algorithms.keywordTrendIntent("what is trending in solar energy?"))
  assertFalse(Algorithms.keywordTrendIntent("keywords for climate research"))
 }
 private fun trendArticle(id:String,title:String,body:String="",host:String="paper.example",at:Long=1_000_000L,publishedAt:Long?=at,language:String="und")=
  TrendArticle(id,title,"https://$host/$id",body,publishedAt,at,at,host,id,"https://$host/rss",language)

 @Test fun topicalCoverageOutranksRepeatedGrammaticalFiller(){
  val current=listOf(trendArticle("a","Nvidia announces semiconductor industry plans","Pode mais para investidores",host="a.example"),trendArticle("b","Semiconductor industry responds to Nvidia","Pode mais para analysts",host="b.example"),trendArticle("c","Semiconductor industry expands","Pode mais para markets",host="c.example"))
  val result=KeywordTrends.analyze(current,emptyList(),TrendFeedHealth(3,0,1_000_000L),0L,1_000_000L,"all")
  assertFalse("pode" in KeywordTrends.terms(current[0]))
  assertEquals("semiconductor industry",result.report.keywords.first().key)
  assertEquals(3,result.report.keywords.first().currentArticles)
 }
 @Test fun trendingCardsAndTheirTextAndEvidenceFollowSupportCounts(){
  val current=buildList{
   repeat(3){index->add(trendArticle("medium-$index","Quantum Battery",host="medium$index.example"))}
   repeat(4){index->add(trendArticle("high-$index","Solar Energy",host="high$index.example"))}
  }
  val result=KeywordTrends.analyze(current,emptyList(),TrendFeedHealth(7,0,1_000_000L),0L,1_000_000L,"all")
  val topics=result.report.keywords.filter{it.key in setOf("solar energy","quantum battery")}
  assertEquals(listOf("solar energy","quantum battery"),topics.map{it.key})
  assertTrue(result.text.indexOf("Solar Energy:")<result.text.indexOf("Quantum Battery:"))
  assertEquals(listOf("high-0","high-1","medium-0","medium-1"),result.supporting.map{it.id})
 }
 @Test fun trendExtractionHandlesShortFieldsLanguageAndCase(){
  assertTrue(KeywordTrends.terms(trendArticle("empty","","")).isEmpty())
  assertTrue(KeywordTrends.terms(trendArticle("short","IA")).isEmpty())
  for(language in listOf("pt","und")){
  val terms=KeywordTrends.terms(trendArticle("pt","PODE MAIS PARA Banco Central contra medidas",language=language))
  for(filler in listOf("pode","mais","para","contra"))assertFalse(filler,filler in terms)
   assertEquals("Banco Central",terms["banco central"])
  }
  for(language in listOf("en","und")){
    val terms=KeywordTrends.terms(trendArticle("en","They SHOULD say there is more news about Solar Energy; this is not correct",language=language))
   for(filler in listOf("they","should","say","there","more","about"))assertFalse(filler,filler in terms)
    assertFalse("not", "not" in terms)
   assertEquals("Solar Energy",terms["solar energy"])
  }
  val standaloneTerms=KeywordTrends.terms(trendArticle("low-signal","appeared Times Show world day days life people two Rio dois pessoas vida"))
  for(term in listOf("appeared","times","show","world","day","days","life","people","two","rio","dois","pessoas","vida"))assertFalse("$term should require a composed phrase",term in standaloneTerms)
  val composedTerms=KeywordTrends.terms(trendArticle("composed","peaceful world; Financial Times; morning show; people appeared; quality of life; Rio de Janeiro; two people; vida pessoal"))
  for(phrase in listOf("peaceful world","financial times","morning show","people appeared","quality of life","rio de janeiro","two people","vida pessoal"))assertTrue(phrase,phrase in composedTerms)
  assertFalse("ia" in KeywordTrends.terms(trendArticle("lower","ia")))
  assertFalse("2026" in KeywordTrends.terms(trendArticle("date","2026 10 12")))
  assertEquals("GPT-6",KeywordTrends.terms(trendArticle("model","GPT-6"))["gpt-6"])
  assertFalse("us" in KeywordTrends.terms(trendArticle("pronoun","They showed US$ 20 and us")))
 }
 @Test fun irregularWhitespaceDoesNotBypassNormalizedStopwords(){
  assertFalse(KeywordTrends.allowedToken("  US","en"))
  assertFalse(KeywordTrends.allowedToken("IA","en"))
  assertFalse(KeywordTrends.allowedToken("  EU ","en"))
  assertFalse(KeywordTrends.allowedToken("  article  ","en"))
  assertTrue(KeywordTrends.allowedToken("Solar","en"))
 }
 @Test fun extractedTokenAndPhraseLabelsAreTrimmedBeforeStorage(){
  val terms=KeywordTrends.terms(trendArticle("spaced","  fim   Banco Central  "))
  assertEquals("fim",terms["fim"])
  assertEquals("Banco Central",terms["banco central"])
 }
 @Test fun standaloneKeywordMatchesWholeNormalizedPhraseWordsOnly(){
  assertEquals(setOf("solar","energy"),KeywordTrends.standaloneDuplicates(listOf("Solar Energy","solar","energy","energ","renewable")))
 }
 @Test fun standaloneDuplicateRemovalRefillsTheEightTopicLimit(){
  val orders=listOf(
   "market policy climate budget reform growth future supply",
   "climate market reform policy future budget supply growth",
   "reform market future policy supply climate growth budget",
   "future market supply policy budget growth climate reform",
   "budget market climate supply future reform policy growth"
  )
  val current=orders.mapIndexed{index,words->trendArticle("rank$index","${if(index<2)"Solar Energy" else "Solar"} $words",host="rank$index.example")}
  val keys=KeywordTrends.analyze(current,emptyList(),TrendFeedHealth(5,0,1_000_000L),0L,1_000_000L,"all").report.keywords.map{it.key}
  assertEquals(8,keys.size)
  assertTrue("solar energy" in keys)
  assertFalse("energy" in keys)
  assertTrue("reform" in keys)
 }
 @Test fun overlappingPhraseIsStoredAsParentVariation(){
  assertTrue(KeywordTrends.overlappingPhrases("Fim da escala","Escala 6x1"))
  assertFalse(KeywordTrends.overlappingPhrases("Fim da escala","Escalaine 6x1"))
  assertFalse(KeywordTrends.overlappingPhrases("Favor da recuperacao","Fim da escala"))
  val current=buildList{
    for(index in 1..2)add(trendArticle("parent$index","Fim da escala",host="parent$index.example"))
    for(index in 1..4)add(trendArticle("child$index","Escala 6x1",host="child$index.example"))
    for(index in 1..2)add(trendArticle("sibling$index","Escala nacional",host="sibling$index.example"))
   add(trendArticle("shared","Fim da escala. Escala 6x1",host="shared.example"))
   for((index,topic) in listOf("quantum","nebula","harbor","orchid","copper","forest","planet","signal","winter","canvas","rocket","island","velvet","comet").withIndex()){
    add(trendArticle("filler$index",topic,host="filler$index.example"))
   }
  }
  val previous=listOf(trendArticle("parent-old","Fim da escala",host="old-parent.example"),trendArticle("child-old","Escala 6x1",host="old-child.example"),trendArticle("sibling-old","Escala nacional",host="old-sibling.example"))
  val report=KeywordTrends.analyze(current,previous,TrendFeedHealth(5,0,1_000_000L),0L,1_000_000L,"all").report
  val parent=report.keywords.first{it.key=="escala 6x1"}
  assertEquals(listOf("Fim da escala","Escala nacional"),parent.variations)
  assertEquals(9,parent.currentArticles)
  assertEquals(3,parent.previousArticles)
  assertEquals(9,parent.supports.map{it.url}.distinct().size)
  assertTrue(parent.supports.any{it.url=="https://child1.example/child1"})
  assertTrue(parent.supports.any{it.url=="https://shared.example/shared"})
  assertFalse(report.keywords.any{it.key=="fim da escala"||it.key=="escala nacional"})
 }
 @Test fun repeatedPhraseNeedsIndependentArticlesAndPublishers(){
  val articles=listOf(trendArticle("a","Quantum battery opens",host="one.example"),trendArticle("a","Quantum battery opens",host="one.example"),trendArticle("b","Quantum battery improves",host="two.example"),trendArticle("c","Unrelated shipment",host="three.example"))
  val result=KeywordTrends.analyze(articles,emptyList(),TrendFeedHealth(3,0,1_000_000L),0L,1_000_000L,"all")
  val topic=result.report.keywords.first{it.key=="quantum battery"}
  assertEquals(2,topic.currentArticles)
  assertEquals(2,topic.currentPublishers)
  assertEquals(setOf("https://one.example/a","https://two.example/b"),topic.supports.map{it.url}.toSet())
  assertFalse(result.report.keywords.any{it.key=="shipment"})
 }
 @Test fun matchingNameFragmentsShareOneCardButIndependentComponentRemains(){
  val current=listOf(trendArticle("a","Luiz Inácio Lula da Silva anuncia reforma",host="one.example"),trendArticle("b","Luiz Inácio Lula da Silva comenta reforma",host="two.example"),trendArticle("c","Silva lidera projeto cultural",host="three.example"),trendArticle("d","Silva apresenta exposição",host="four.example"))
  val previous=listOf(trendArticle("e","Luiz Inácio Lula da Silva anuncia plano",host="one.example"),trendArticle("f","Luiz Inácio Lula da Silva comenta plano",host="two.example"),trendArticle("g","Silva prepara exposição",host="three.example"))
  val result=KeywordTrends.analyze(current,previous,TrendFeedHealth(4,0,1_000_000L),0L,1_000_000L,"all")
  val keywords=result.report.keywords
  assertTrue(keywords.any{it.key=="luiz inacio lula"&&it.currentArticles==2&&it.previousArticles==2})
  assertFalse(keywords.any{it.key=="luiz inacio"||it.key=="inacio lula"||it.key=="lula da silva"})
  assertTrue(keywords.any{it.variations.orEmpty().contains("Lula da Silva")})
  assertFalse(keywords.any{it.key=="silva"})
 }

 @Test fun keywordWindowsAreEqualAndRespectExplicitDates(){
  val now=10_000_000L;val window=KeywordTrends.window(now,0L,ResearchFilters())
  assertEquals(3_600_000L,window.end-window.start)
  assertEquals(window.end-window.start,window.start-window.precedingStart)
  assertTrue(window.baselineComplete)
  val custom=KeywordTrends.window(now,0L,ResearchFilters(fromAt=now-1_800_000L,toAt=now-600_000L,trendMinutes=120))
  assertEquals(1_200_000L,custom.end-custom.start)
  assertEquals(custom.end-custom.start,custom.start-custom.precedingStart)
  assertFalse(KeywordTrends.window(now,now-1_000_000L,ResearchFilters()).baselineComplete)
 }
 @Test fun repeatedWordsAndSyndicatedFeedsCountOncePerArticleAndPublisher(){
  val current=listOf(trendArticle("a","Solar solar solar energy","solar ".repeat(30),"paper.example"),trendArticle("b","Solar investment","solar ".repeat(20),"paper.example"),trendArticle("c","Solar panels","solar ".repeat(10),"other.example"))
  val previous=listOf(trendArticle("d","Solar rollout",host="paper.example"),trendArticle("e","Solar panels",host="other.example"),trendArticle("f","Unrelated sports",host="third.example"))
  val result=KeywordTrends.analyze(current,previous,TrendFeedHealth(3,0,1_000_000L),0L,1_000_000L,"pt,en,und")
  assertFalse(result.report.keywords.any{it.key=="solar"})
  val solarTopics=result.report.keywords.filter{"solar" in it.key.split(Regex("\\s+"))}
  assertTrue(solarTopics.isNotEmpty())
  assertTrue(result.text.contains("https://paper.example/a"))
  assertTrue(result.text.contains("https://other.example/c"))
  assertEquals(3,solarTopics.flatMap{it.supports}.map{it.url}.distinct().size)
 }
 @Test fun trendClutterIsRemovedBeforeCountingInBothWindows(){
  val current=listOf(trendArticle("a","Nesta terça: Banco Central anuncia GPT-6 - AP News","<script>junkterm</script> &amp; Leia mais https://site.example/clutterterm; energia artificial",host="first.example"),trendArticle("b","Banco Central adota inteligência artificial | Europe live","www.site.example/clutterterm Continue reading",host="second.example"),trendArticle("c","Inteligência artificial no Banco Central","arXiv:2610.12855v1 Announce Type: new Abstract: inteligência artificial",host="third.example"))
  val previous=listOf(trendArticle("d","Banco Central define regras",host="first.example"),trendArticle("e","Inteligência artificial avança",host="second.example"),trendArticle("f","Other subject",host="third.example"))
  val result=KeywordTrends.analyze(current,previous,TrendFeedHealth(3,0,1_000_000L),0L,1_000_000L,"all")
  assertTrue(result.text.contains("Banco Central: 3 articles / 3 publishers; previous 1 articles / 1 publishers."))
  assertEquals("Inteligência artificial",KeywordTrends.terms(current[2])["inteligencia artificial"])
  assertTrue("inteligencia artificial" in KeywordTrends.terms(previous[1]))
  assertFalse(result.text.contains("increase in indexed coverage"))
  val keys=(current+previous).flatMap{KeywordTrends.terms(it).keys}.toSet()
  for(clutter in listOf("nesta","ap news","europe live","junkterm","clutterterm","arxiv","announce","abstract","continue reading","amp"))assertFalse(clutter,clutter in keys)
 }
 @Test fun trendLabelsRetainNamesAcronymsShortTokensAndNumbersInsideEntities(){
  val current=listOf(trendArticle("a","Banco do Brasil e GPT-6: IA no DF"),trendArticle("b","Inteligência Artificial e GPT-6 no Banco do Brasil",host="other.example"),trendArticle("c","Banco do Brasil testa GPT-6",host="third.example"))
  val result=KeywordTrends.analyze(current,emptyList(),TrendFeedHealth(3,0,1_000_000L),0L,1_000_000L,"all")
  assertTrue(result.text.contains("Banco do Brasil: 3 articles / 3 publishers"))
  assertTrue(result.text.contains("GPT-6: 3 articles / 3 publishers"))
  assertEquals("Inteligência Artificial",KeywordTrends.terms(current[1])["inteligencia artificial"])
  assertFalse("ia" in KeywordTrends.terms(current[0]))
  assertFalse("df" in KeywordTrends.terms(current[0]))
  assertFalse("e" in KeywordTrends.terms(current[0]))
  assertFalse("s" in KeywordTrends.terms(trendArticle("possessive","Ukraine’s energy ceasefire")))
  assertFalse("segunda-feira" in KeywordTrends.terms(trendArticle("weekday","Feriado nesta segunda-feira")))
 }
 @Test fun ambiguousGrammarCountryAndCurrencyUseActualContext(){
  val article=trendArticle("context","São Paulo announces US exports and US$ 20 million for One Direction","Enquanto são fortes, they give us one more chance over the week. São Paulo and the US agree. US$50 is only a price.")
  val terms=KeywordTrends.terms(article)
  for(filler in listOf("one","enquanto","over","sao","week","out","that","deve","foram","estao","presidente","ano","anos","year","years"))assertFalse(filler,filler in terms)
  assertEquals("São Paulo",terms["sao paulo"])
  assertEquals("One Direction",terms["one direction"])
  assertFalse("us" in terms)
  assertFalse("us 20" in terms)
  val bodyOnly=trendArticle("body","Exports rise","One Direction plays in São Paulo; they told us US$5 was paid while US exports grew")
  assertEquals("São Paulo",KeywordTrends.terms(bodyOnly)["sao paulo"])
  assertEquals("One Direction",KeywordTrends.terms(bodyOnly)["one direction"])
  assertFalse("us" in KeywordTrends.terms(bodyOnly))
  assertFalse("us" in KeywordTrends.terms(trendArticle("pronoun","They showed us around","They told us the news")))
  assertFalse("us" in KeywordTrends.terms(trendArticle("money","US$ 50 deal","US$20 more")))
  assertEquals("Fashion Week",KeywordTrends.terms(trendArticle("event","Fashion Week opens"))["fashion week"])
  assertEquals("Presidente Prudente",KeywordTrends.terms(trendArticle("city","Presidente Prudente", "presidente Donald Trump visited Presidente Prudente"))["presidente prudente"])
  val country=KeywordTrends.analyze(listOf(trendArticle("country","Trade dispute","They told us US$10 was paid, but the US president denied it")),emptyList(),TrendFeedHealth(1,0,1_000_000L),0L,1_000_000L,"all")
  assertFalse(country.report.keywords.any{it.key=="us"})
 }
 @Test fun overlapAndFreshnessAreEvidenceSpecific(){
  val recent=listOf(trendArticle("a","Energy ceasefire confirmed",host="a.example"),trendArticle("b","Energy ceasefire debated",host="b.example"),trendArticle("c","Energy ceasefire contested",host="c.example"))
  val previous=listOf(trendArticle("d","Energy ceasefire planned",host="a.example"),trendArticle("e","Energy ceasefire discussed",host="b.example"),trendArticle("f","Unrelated article",host="c.example"))
  val fresh=(recent+previous).associate{it.sourceId to 1_000L}
  val result=KeywordTrends.analyze(recent,previous,TrendFeedHealth(58,9,1_000L),0L,1_000_000L,"all",sourceRefresh=fresh)
  assertTrue(result.report.keywords.any{it.key=="energy ceasefire"&&it.currentArticles==3&&it.previousArticles==2})
  assertFalse(result.report.keywords.any{it.key=="energy"||it.key=="ceasefire"})
  assertTrue(result.report.keywords.first().status.contains("wider coverage uncertain"))
  assertEquals("Energy ceasefire confirmed",result.report.keywords.first().supports.first().excerpt)
  assertEquals(3,result.report.keywords.first().supports.size)
  val stale=KeywordTrends.analyze(recent,previous,TrendFeedHealth(58,9,1_000L),100L,1_000_000L,"all",sourceRefresh=fresh+("b" to 0L))
  assertTrue(stale.report.keywords.first().status.contains("supporting sources stale"))
  assertFalse(stale.report.keywords.first().status.contains("increase"))
  val separate=KeywordTrends.analyze(recent+listOf(trendArticle("market","Energy market rises",host="market.example"),trendArticle("grid","Energy grid grows",host="grid.example")),previous,TrendFeedHealth(5,0,1_000L),0L,1_000_000L,"all")
  assertFalse(separate.report.keywords.any{it.key=="energy"})
  assertTrue(separate.report.keywords.any{it.key=="energy ceasefire"})
 }
 @Test fun keywordReportRoundTripLinksAndBrasiliaRollover(){
  val result=KeywordTrends.analyze(listOf(trendArticle("a","São Paulo","São Paulo agora",publishedAt=null)),emptyList(),TrendFeedHealth(1,0,0L),0L,1_000_000L,"all")
  val saved=KeywordReportCodec.decode(KeywordReportCodec.encode(result.report))!!
  assertEquals(result.report.keywords.map{it.key},saved.keywords.map{it.key})
  val legacyJson=com.google.gson.JsonParser.parseString(KeywordReportCodec.encode(result.report)).asJsonObject.apply{
   getAsJsonArray("keywords").forEach{it.asJsonObject.remove("variations")}
  }
  val legacy=KeywordReportCodec.decode(legacyJson.toString())!!
  assertTrue(legacy.keywords.all{it.variations==emptyList<String>()})
  val reordered=com.google.gson.JsonParser.parseString(KeywordReportCodec.encode(result.report)).asJsonObject
  val androidOrder=com.google.gson.JsonObject().apply{add("baselineSparse",reordered.get("baselineSparse"));reordered.entrySet().filter{it.key!="baselineSparse"}.forEach{add(it.key,it.value)}}
  assertEquals(saved.keywords.first().key,KeywordReportCodec.decode(androidOrder.toString())!!.keywords.first().key)
  assertEquals(result.report.keywords.first().supports.first().url,saved.keywords.first().supports.first().url)
  assertNull(KeywordReportCodec.decode("Older saved report: readable plain text"))
  assertEquals("https://paper.example/a",TrendDisplay.articleUrl("https://paper.example/a"))
  assertNull(TrendDisplay.articleUrl("javascript:alert(1)"))
  assertTrue(TrendDisplay.articleTime(saved.keywords.first().supports.first()).startsWith("First seen "))
  assertEquals("11/10/2026 21:30 Brasília (America/Sao_Paulo)",TrendDisplay.time(java.time.Instant.parse("2026-10-12T00:30:00Z").toEpochMilli()))
 }
 @Test fun sparseBaselineAndMissingDatesNeverClaimVerifiedIncrease(){
  val current=listOf(trendArticle("a","Energy market",publishedAt=null),trendArticle("b","Energy prices",host="other.example"),trendArticle("c","Energy costs",host="third.example"))
  val previous=listOf(trendArticle("d","Energy supply"))
  val sparse=KeywordTrends.analyze(current,previous,TrendFeedHealth(3,0,1_000_000L),0L,1_000_000L,"all")
  assertTrue(sparse.text.contains("Sparse baseline"))
  assertTrue(sparse.text.contains("1 without publication dates"))
  assertTrue(sparse.text.contains("first seen 31/12/1969 21:16 Brasília (America/Sao_Paulo), publication unknown"))
  assertFalse(sparse.text.contains("increase in indexed coverage"))
  val baseline=listOf(trendArticle("d","Energy supply"),trendArticle("e","Energy forecast",host="other.example"),trendArticle("f","Other subject",host="third.example"))
  val undated=KeywordTrends.analyze(current,baseline,TrendFeedHealth(3,0,1_000_000L),0L,1_000_000L,"all")
  assertTrue(undated.text.contains("publication dates missing; no verified trend"))
  assertFalse(undated.text.contains("increase in indexed coverage"))
  val incomplete=KeywordTrends.analyze(current.map{it.copy(publishedAt=it.effectiveAt)},baseline,TrendFeedHealth(3,0,1_000_000L),0L,1_000_000L,"all",false)
  assertTrue(incomplete.text.contains("Sparse baseline"))
 }
 @Test fun staleSourcesBlockVerifiedTrendAndExplicitPublisherSelectionRestrictsFeeds(){
  val sources=listOf(SourceEntity("a","https://www.paper.example/rss","A"),SourceEntity("b","https://paper.example/atom","B"),SourceEntity("c","https://other.example/rss","C"))
  assertEquals(listOf("b"),LocalRetriever.selectedSourceIds(sources,ResearchFilters(sourceIds=setOf("b","c"),publishers=setOf("paper.example"))))
  assertEquals(emptyList<String>(),LocalRetriever.selectedSourceIds(sources,ResearchFilters(sourceIds=setOf("unknown"))))
  val current=listOf(trendArticle("a","Solar energy"),trendArticle("b","Solar prices",host="other.example"),trendArticle("c","Solar power",host="third.example"))
  val prior=listOf(trendArticle("d","Solar panel"),trendArticle("e","Solar grid",host="other.example"),trendArticle("f","Other issue",host="third.example"))
  val stale=KeywordTrends.analyze(current,prior,TrendFeedHealth(3,1,0L),0L,1_000_000L,"en")
  assertTrue(stale.text.contains("Stale or unrefreshed feed coverage"))
  assertTrue(stale.text.contains("stale feed coverage; no verified trend"))
  assertFalse(stale.text.contains("increase in indexed coverage"))
 }
 private fun article(id:String,title:String,text:String,source:String=id,url:String="https://$source.example/rss",effectiveAt:Long=100L,tags:String="",language:String="und")=
  Candidate("$id:0",id,"$title\n$text",null,"",title,"https://$source.example/$id",source,source,null,"hash-$id",effectiveAt=effectiveAt,sourceUrl=url,tags=tags,language=language)

 @Test fun equivalentPortugueseEnglishQueriesPreserveEvidenceText(){
  val docs=listOf(article("a","Energy prices rise","New energy prices rise after supplies fall"),article("b","Unrelated sports","The match ended yesterday"))
  val portuguese=LocalRetriever.rank("preços de energia",docs)
  val english=LocalRetriever.rank("energy prices",docs)
  assertEquals(listOf(0),portuguese.ranked)
  assertEquals(portuguese.ranked,english.ranked)
  assertEquals("Energy prices rise",docs[0].title)
  assertEquals("New energy prices rise after supplies fall",docs[0].text.substringAfter('\n'))
  assertEquals(listOf("health"),Algorithms.searchTokens("Saúde"))
  assertEquals(listOf("health"),Algorithms.searchTokens("health"))
 }
 @Test fun weakSharedKeywordsDoNotOverrideTopicalCoverage(){
  val docs=listOf(article("sports","Sports prices rose","The team sold solar memorabilia"),article("solar","Solar energy prices","Solar energy prices fell despite strong demand"))
  val original=Algorithms.bm25("solar energy prices",docs.map{it.title+" "+it.text})
  val improved=LocalRetriever.rank("solar energy prices",docs)
  assertTrue(original.contains(0))
  assertEquals(listOf(1),improved.ranked)
  assertTrue(improved.reasons[1]!!.contains("3/3"))
  assertTrue(LocalRetriever.rank("unexpected unrelated inquiry",docs).limitation.isNotBlank())
 }
 @Test fun sourceTagsAreOnlySmallHintsForMatchingArticles(){
  val docs=listOf(article("a","Health update","Hospital treatment changed",tags="health"),article("b","Football update","The team won",tags="health"))
  assertEquals(listOf(0),LocalRetriever.rank("health",docs).ranked)
  assertTrue(LocalRetriever.rank("health",docs).reasons[0]!!.contains("source-tag hint"))
 }
 @Test fun latestNewsStaysBroadWhileTopicalNewsRequiresRelevantTerms(){
  val docs=listOf(article("sports","Match result","Sports update",effectiveAt=300),article("climate","Climate change","Climate change reported",effectiveAt=200),article("clima","Clima mudou","Mudanças no clima",effectiveAt=100))
  assertEquals(listOf(0,1,2),LocalRetriever.rank("Quais são as últimas notícias?",docs).ranked)
  assertEquals(listOf(0,1,2),LocalRetriever.rank("Latest news?",docs).ranked)
  assertEquals(listOf(1,2),LocalRetriever.rank("Últimas notícias sobre clima",docs).ranked)
  assertEquals(listOf(1,2),LocalRetriever.rank("Latest news about climate",docs).ranked)
 }
 @Test fun explicitFiltersAndUnknownLanguageHaveBoundedParameters(){
  val selected=ResearchFilters(sourceIds=setOf("feedA"),publishers=setOf("paper.example"),topic="100%_!",languages=setOf("pt","en","und"))
  assertEquals(setOf("pt","en","und"),selected.languages)
  assertEquals(setOf("feedA"),selected.sourceIds)
  assertEquals(setOf("paper.example"),selected.publishers)
  assertEquals("%100!%!_!!%",LocalRetriever.topicPatterns(selected.topic).first)
  assertEquals("%clima%",LocalRetriever.topicPatterns("climate").second)
  assertEquals("paper.example",LocalRetriever.publisher("https://www.paper.example/feed1"))
  assertEquals("pt",Algorithms.detectLanguage("Notícias sobre o país hoje, com medidas para famílias"))
  assertEquals("en",Algorithms.detectLanguage("The report about their latest news today"))
  assertEquals("und",Algorithms.detectLanguage("CVE-2026-1234"))
 }
 @Test fun researchLanguageModesMapToStoredLanguageCodes(){
  assertEquals(setOf("pt"),ResearchLanguageMode.PORTUGUESE.languageCodes)
  assertEquals(setOf("en","und"),ResearchLanguageMode.ENGLISH.languageCodes)
  assertEquals(setOf("pt","en","und"),ResearchLanguageMode.BOTH.languageCodes)
  assertEquals(ResearchLanguageMode.ENGLISH,ResearchLanguageMode.fromCodes(setOf("en","und")))
  assertEquals("pt-BR",ResearchLanguageMode.PORTUGUESE.speechLocale)
  assertEquals("Portuguese language intput",ResearchLanguageMode.PORTUGUESE.speechUiLabel)
  assertEquals("en-US",ResearchLanguageMode.ENGLISH.speechLocale)
  assertEquals("English language intput",ResearchLanguageMode.ENGLISH.speechUiLabel)
  assertEquals("en",ResearchLanguageMode.BOTH.speechLocale)
  assertEquals("English language intput",ResearchLanguageMode.BOTH.speechUiLabel)
 }
 @Test fun publisherDiversityDiffersFromFeedDiversityAndKeepsDisagreement(){
  val docs=listOf(article("a","Rates rise","The bank raised rates",source="one",url="https://www.paper.example/rss"),article("b","Rates fall","The bank lowered rates",source="two",url="https://paper.example/atom"),article("c","Rates unchanged","The bank held rates",source="three",url="https://other.example/rss"))
  val plan=EvidencePlanner.plan(docs,listOf(0,1,2),emptyMap(),Config(topK=3),"rates", "r",300L)
  assertEquals(3,plan.evidence.size)
  assertEquals(3,plan.evidence.map{it.source}.distinct().size)
  assertEquals(2,docs.map{LocalRetriever.publisher(it.sourceUrl)}.distinct().size)
  assertTrue(plan.evidence.any{it.text.contains("lowered")})
  assertTrue(plan.evidence.any{it.text.contains("raised")})
 }
 @Test fun localRetrievalNeedsNoNetworkEvenWhenEmbeddingsAreEnabled(){
  val before=java.net.ProxySelector.getDefault()
  try{
   java.net.ProxySelector.setDefault(object:java.net.ProxySelector(){override fun select(uri:java.net.URI):List<java.net.Proxy>{error("Network lookup during local filtering")};override fun connectFailed(uri:java.net.URI,address:java.net.SocketAddress,error:java.io.IOException){}})
   val docs=listOf(article("one","Economia cresce","Economy improves",language="und"))
   assertEquals(listOf(0),LocalRetriever.rank("economy",docs).ranked)
   assertTrue(Config(embeddings=true).embeddings)
  }finally{java.net.ProxySelector.setDefault(before)}
 }
 @Test fun latestNewsIntentInPortugueseAndEnglish(){
  assertEquals("",Algorithms.latestTopic("Quais são as últimas notícias?"))
  assertEquals("",Algorithms.latestTopic("Latest news?"))
  assertEquals("clima",Algorithms.latestTopic("Notícias mais recentes sobre clima"))
  assertEquals("climate change",Algorithms.latestTopic("Latest news about climate change?"))
  assertNull(Algorithms.latestTopic("What is happening with climate change?"))
  assertNull(Algorithms.latestTopic("CVE-2026-1234 latest patch"))
 }
 @Test fun broadNewsRanksRecentUniqueArticlesAndTopicKeepsRelevance(){
  val docs=listOf(
   Candidate("new:0","new","Latest science",null,"","Science","https://new.test","Feed A","a",200L,"new-hash",effectiveAt=200L),
   Candidate("new:1","new","Another part",null,"","Science","https://new.test","Feed A","a",200L,"new-hash",effectiveAt=200L),
   Candidate("climate:0","climate","Climate news",null,"","Climate","https://climate.test","Feed B","b",150L,"climate-hash",effectiveAt=150L),
   Candidate("older:0","older","Climate details",null,"","Climate","https://older.test","Feed C","c",null,"older-hash",effectiveAt=100L,firstSeenAt=100L)
  )
  assertEquals(listOf(0,2,3),NewsRanking.rank(docs,"",emptyList()))
  assertEquals(listOf(2,3),NewsRanking.rank(docs,"climate",listOf(3,2)))
  assertTrue(NewsRanking.rank(docs,"economy",emptyList()).isEmpty())
  val plan=EvidencePlanner.plan(docs,NewsRanking.rank(docs,"",emptyList()),emptyMap(),Config(topK=3),"Latest news", "r",300L,1400)
  assertEquals(3,plan.evidence.size)
  assertEquals(100L,plan.evidence.last().firstSeenAt)
  assertNull(plan.evidence.last().publishedAt)
 }
 @Test fun exhaustedReasoningBudgetIsNotACompletedAnswer(){
  assertEquals("FAILED",generationStatus("","length",true,""))
  assertEquals("TRUNCATED",generationStatus("Partial answer","length",true,""))
  assertEquals("FAILED",generationStatus("","",false,"HTTP 429"))
  assertEquals("COMPLETE",generationStatus("Answer","stop",true,""))
 }
 @Test fun evidenceBudgetAndSourceCoverageOnIdenticalInputs(){
  val docs=(1..12).map{index->Candidate("a$index:0","a$index","Signal $index: "+"detail $index ".repeat(60),null,"","Title $index","https://feed$index.test/a","Feed $index","source$index",null,"hash$index")}
  val ranked=docs.indices.toList()
  val oldTopEight=ranked.take(8).map{docs[it].sourceId}.toSet()
  val bodies=docs.associate{it.articleId to ("Extended source detail ${it.sourceId}. "+it.text)}
  val config=Config(model="z-ai/glm-5.3-flash",topK=12,contextTokens=2_000_000)
  val plan=EvidencePlanner.plan(docs,ranked,bodies,config,"signal", "report",100L)
  assertEquals(1_048_576,plan.contextLimit)
  assertEquals(8,oldTopEight.size)
  assertEquals(12,plan.evidence.map{it.source}.toSet().size)
  assertEquals(12,plan.evidence.size)
    println("Identical candidate coverage: old=$oldTopEight new=${plan.evidence.map{it.source}.toSet().size} sources; synthesis not measured")
  assertTrue(plan.evidence.first().text.startsWith("Extended source detail"))
  assertTrue(plan.estimatedInputTokens+plan.outputLimit<=plan.contextLimit)
 }
 @Test fun unknownModelUsesConservativeBudgetAndDeduplicatesOverlaps(){
  val body="New analysis details. "+"repeatable background ".repeat(400)
  val docs=(1..12).map{index->Candidate("a$index:0","a$index","Relevant $index "+"specific $index ".repeat(30),null,"","Title $index","https://feed$index.test/a","Feed $index","source$index",null,"hash$index")}
  val plan=EvidencePlanner.plan(docs,docs.indices.toList(),docs.associate{it.articleId to body},Config(model="openrouter/auto",topK=12,contextTokens=32768),"What changed?","report",100L)
  assertEquals(8192,plan.contextLimit)
  assertTrue(plan.estimatedInputTokens+plan.outputLimit<=plan.contextLimit)
  assertTrue(plan.evidence.isNotEmpty())
  assertTrue(plan.evidence.size<12)
  assertTrue(plan.clipped>0)
  val duplicate=docs[0].copy(id="other:0",articleId="other",sourceId="other",hash=docs[0].hash)
  val dedup=EvidencePlanner.plan(listOf(docs[0],duplicate),listOf(0,1),emptyMap(),Config(),"Relevant","report",100L)
  assertEquals(1,dedup.evidence.size)
 }
 @Test fun staleOrDisabledSourcesCannotApplyFetchedData(){
  val snapshot=SourceEntity("s","https://example.com/rss","Source")
  assertTrue(sourceStillCurrent(snapshot,snapshot))
  assertFalse(sourceStillCurrent(null,snapshot))
  assertFalse(sourceStillCurrent(snapshot.copy(enabled=false,revision=1),snapshot))
  assertFalse(sourceStillCurrent(snapshot.copy(revision=1),snapshot))
 }
 @Test fun refreshIsBoundedAndFasterOnIdenticalDelayedInputs() = runBlocking {
  val inputs=(1..12).toList()
  suspend fun measure(limit:Int):Pair<Long,Int>{
   var active=0;var peak=0
   val elapsed=measureTimeMillis {RefreshRunner.run(inputs,limit,{item->
    active++;peak=maxOf(peak,active);delay(60);active--;item
   },{_,result->assertTrue(result.isSuccess)})}
   return elapsed to peak
  }
  val serial=measure(1);val parallel=measure(3);val concurrent=measure(10)
    println("Identical delayed refresh inputs: serial=${serial.first}ms bounded3=${parallel.first}ms bounded10=${concurrent.first}ms")
  assertEquals(1,serial.second)
  assertEquals(3,parallel.second)
  assertEquals(10,concurrent.second)
  assertTrue("serial=${serial.first}ms parallel=${parallel.first}ms",parallel.first<serial.first)
 }
 @Test fun refreshConcurrencyIsCappedAt100() = runBlocking {
  var active=0;var peak=0
  RefreshRunner.run((1..101).toList(),200,{item->
   active++;peak=maxOf(peak,active);delay(10);active--;item
  },{_,result->assertTrue(result.isSuccess)})
  assertEquals(100,peak)
 }
 @Test fun refreshFailuresDoNotAbortOtherFeeds() = runBlocking {
  val processed=mutableListOf<Int>();var failures=0
  RefreshRunner.run((1..5).toList(),3,{item->if(item==2)error("HTTP 500");item},{item,result->
   if(result.isSuccess)processed.add(item) else failures++
  })
  assertEquals(listOf(1,3,4,5),processed)
  assertEquals(1,failures)
 }
 @Test fun refreshCancellationStopsFutureBatches() = runBlocking {
  val started=mutableListOf<Int>()
  val job=launch{RefreshRunner.run((1..9).toList(),3,{item->started.add(item);delay(10_000);item},{_,_->})}
  while(started.size<3)delay(1)
  job.cancelAndJoin()
  assertTrue(job.isCancelled)
  assertEquals(listOf(1,2,3),started)
 }
 @Test fun bulkImportSkipsDuplicatesAndContinuesAfterFailures() = runBlocking {
  val attempted=mutableListOf<String>()
  val updates=mutableListOf<FeedImportState>()
  val result=FeedImporter.run("\n https://old.test/rss \nhttps://new.test/a\nhttps://new.test/a\nhttps://bad.test/rss\nhttps://new.test/b\n",setOf("https://old.test/rss"),{url->
   attempted.add(url)
   if(url.contains("bad"))error("HTTP 404")
   true
  },{updates.add(it)})
  assertEquals(3,attempted.size)
  assertEquals(5,result.total)
  assertEquals(2,result.added)
  assertEquals(2,result.skipped)
  assertEquals(1,result.failed)
  assertEquals(5,result.completed)
  assertEquals(listOf("https://bad.test/rss: HTTP 404"),result.failures)
  assertFalse(result.running)
  assertTrue(updates.any{it.running && it.completed<it.total})
 }
 @Test fun bulkImportBoundsConcurrencyAndCountsCapacitySkips() = runBlocking {
  var active=0;var peak=0
  val result=FeedImporter.run((1..8).joinToString("\n"){"https://feed.test/$it"},emptySet(),{url->
   active++
   peak=maxOf(peak,active)
   kotlinx.coroutines.delay(10)
   active--
   !url.endsWith("/8")
  },{})
  assertEquals(3,peak)
  assertEquals(7,result.added)
  assertEquals(1,result.skipped)
  assertEquals(0,result.failed)
 }
 @Test fun retentionIsRollingUtc(){assertEquals(568000000L,Algorithms.cutoff(1000000000L,5))}
 @Test fun exactIdentifiers(){assertEquals(listOf("cve-2026-1234","10.0.0.1"),Algorithms.tokens("CVE-2026-1234 10.0.0.1"))}
 @Test fun lexicalRetrievesSpecificIndicator(){assertEquals(1,Algorithms.bm25("CVE-2026-1234",listOf("general news","CVE-2026-1234 patched")).first())}
 @Test fun chunksOverlap(){val chunks=Algorithms.chunks("abcdefghij",6,2);assertEquals(listOf("abcdef","efghij"),chunks)}
 @Test fun chunksAreBounded(){assertTrue(Algorithms.chunks("x".repeat(20000)).size<=16)}
 @Test fun longFeedContentReachesTheFinalChunk(){
  val text="Short sentence. ".repeat(1000)+"Last feed detail."
  val chunks=Algorithms.chunks(text)
  assertTrue(chunks.size<=16)
  assertTrue(chunks.last().endsWith("Last feed detail."))
 }
 @Test fun relatedSourcesWithDifferentDetailsAreRetained(){
  val common="shared background ".repeat(70)
  val docs=listOf("rate increased", "rate decreased").mapIndexed{index,detail->
   Candidate("c$index","a$index","$common$detail",null,"","Title","https://$index.test/a","Feed $index","s$index",null,"h$index")
  }
  val plan=EvidencePlanner.plan(docs,listOf(0,1),emptyMap(),Config(),"rate","r",0)
  assertEquals(2,plan.evidence.size)
 }
 @Test fun vectorsRoundTrip(){val v=floatArrayOf(1f,0.5f,-2f);assertArrayEquals(v,Algorithms.decode(Algorithms.encode(v)),0f)}
 @Test fun cosine(){assertEquals(1.0,Algorithms.cosine(floatArrayOf(1f,0f),floatArrayOf(1f,0f)),0.0001)}
 @Test fun fusionPrefersAgreement(){assertEquals(2,Algorithms.rrf(listOf(1,2),listOf(2,3)).first())}

 @Test fun sentenceAndParagraphBoundaryChunking(){
  val text = "First sentence. Second sentence. Third sentence. Fourth sentence."
  val chunks = Algorithms.chunks(text, size = 35, overlap = 5)
  assertTrue(chunks.isNotEmpty())
  assertTrue("Chunk should end on sentence boundary", chunks[0].endsWith("."))
 }

 @Test fun longSentenceFallback(){
  val longText = "A".repeat(5000)
  val chunks = Algorithms.chunks(longText, size = 1600, overlap = 200)
  assertTrue(chunks.size in 1..8)
  assertEquals(1600, chunks[0].length)
 }

 @Test fun contentTypeClassificationAndMetadataPersistence(){
  val feedContentItem = FeedItem("1", "Title 1", "https://example.com/1", "Feed content body", null, contentType = "FEED_CONTENT")
  assertEquals("FEED_CONTENT", feedContentItem.contentType)

  val summaryItem = FeedItem("2", "Title 2", "https://example.com/2", "Summary text", null, contentType = "SUMMARY")
  assertEquals("SUMMARY", summaryItem.contentType)

  val paywallItem = FeedItem("3", "Title 3", "https://example.com/3", "Conteúdo exclusivo para assinantes", null, contentType = "PAYWALL_INDICATED")
  assertEquals("PAYWALL_INDICATED", paywallItem.contentType)

  val unknownItem = FeedItem("4", "Title 4", "https://example.com/4", "Text", null, contentType = "UNKNOWN")
  assertEquals("UNKNOWN", unknownItem.contentType)

  val articleEntity = ArticleEntity("a1", "s1", feedContentItem.title, feedContentItem.url, feedContentItem.text, null, 1000L, 1000L, "hash1", feedContentItem.contentType)
  assertEquals("FEED_CONTENT", articleEntity.contentType)
 }

 @Test fun rssProviderParsingContentSelection(){
  val rssXml = """
   <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
    <channel>
     <title>News Feed</title>
     <item>
      <title>Article Full</title>
      <link>https://news.org/1</link>
      <description>Teaser text</description>
      <content:encoded><![CDATA[<p>Full article body with detailed news coverage.</p>]]></content:encoded>
     </item>
     <item>
      <title>Article Summary Only</title>
      <link>https://news.org/2</link>
      <description>Teaser summary text only.</description>
     </item>
    </channel>
   </rss>
  """.trimIndent()

  val rssProvider = RssProvider()
  val feed = rssProvider.parse(rssXml, "https://news.org/rss")
  assertEquals(2, feed.items.size)

  val item1 = feed.items[0]
  assertEquals("FEED_CONTENT", item1.contentType)
  assertTrue(item1.text.contains("Full article body with detailed news coverage"))

  val item2 = feed.items[1]
  assertEquals("SUMMARY", item2.contentType)
  assertTrue(item2.text.contains("Teaser summary text only"))
 }

 @Test fun xxeSecurityProtection(){
  val maliciousXml = """
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE foo [
    <!ELEMENT foo ANY >
    <!ENTITY xxe SYSTEM "file:///etc/passwd" >
   ]>
   <rss version="2.0">
    <channel>
     <title>&xxe;</title>
     <item>
      <title>Malicious Item</title>
      <description>&xxe;</description>
     </item>
    </channel>
   </rss>
  """.trimIndent()

  val rssProvider = RssProvider()
  val feed = runCatching { rssProvider.parse(maliciousXml, "https://example.com/rss") }.getOrNull()
  if (feed != null && feed.items.isNotEmpty()) {
   assertFalse("XXE entity must not be expanded into local file content", feed.items[0].text.contains("root:x:"))
  }
 }

 @Test fun roomMigration1To2SqlStatements(){
  val statements = mutableListOf<String>()
  val mockDb = Proxy.newProxyInstance(
   SupportSQLiteDatabase::class.java.classLoader,
   arrayOf(SupportSQLiteDatabase::class.java)
  ) { _, method, args ->
   if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
    statements.add(args[0].toString())
   }
   null
  } as SupportSQLiteDatabase

  AppDatabase.MIGRATION_1_2.migrate(mockDb)

  assertEquals(2, statements.size)
  assertEquals("ALTER TABLE articles ADD COLUMN contentType TEXT NOT NULL DEFAULT 'UNKNOWN'", statements[0])
  assertEquals("ALTER TABLE evidence ADD COLUMN contentType TEXT NOT NULL DEFAULT 'UNKNOWN'", statements[1])
 }

 @Test fun roomMigration2To3PreservesOldEvidenceRows(){
  val statements=mutableListOf<String>()
  val mockDb=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)){_,method,args->
   if(method.name=="execSQL"&&args!=null)statements.add(args[0].toString())
   null
  } as SupportSQLiteDatabase
  AppDatabase.MIGRATION_2_3.migrate(mockDb)
  assertEquals(listOf("ALTER TABLE evidence ADD COLUMN firstSeenAt INTEGER"),statements)
 }

 @Test fun roomMigration3To4OnlyAddsDefaultedColumns(){
  val statements=mutableListOf<String>()
  val mockDb=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)){_,method,args->
   if(method.name=="execSQL"&&args!=null)statements.add(args[0].toString());null
  } as SupportSQLiteDatabase
  AppDatabase.MIGRATION_3_4.migrate(mockDb)
  assertEquals(listOf("ALTER TABLE sources ADD COLUMN tags TEXT NOT NULL DEFAULT ''","ALTER TABLE articles ADD COLUMN language TEXT NOT NULL DEFAULT 'und'"),statements)
 }

 @Test fun evidenceAssemblyAndCitationMapping(){
  val evidence = EvidenceEntity("r1:0", "r1", 1, "Title 1", "https://e1.com", "Source 1", "Text 1", null, 1000L, "h1", contentType = "FEED_CONTENT")
  assertEquals(1, evidence.citation)
  assertEquals("Title 1", evidence.title)
  assertEquals("Text 1", evidence.text)
  assertEquals("FEED_CONTENT", evidence.contentType)
 }

 @Test fun streamEventStatusIntegrity(){
  val eventDone = StreamEvent(text = "Hello", done = true, finishReason = "stop", promptTokens = 10, completionTokens = 20, totalTokens = 30)
  assertEquals("stop", eventDone.finishReason)
  assertTrue(eventDone.done)

  val eventTruncated = StreamEvent(text = "Partial...", finishReason = "length")
  assertEquals("length", eventTruncated.finishReason)

  val eventError = StreamEvent(error = "Provider error 500")
  assertEquals("Provider error 500", eventError.error)
 }

 @Test fun lengthBaselineAndBoilerplateNoiseFilterBlocksShortWordsAndUIBoilerplate(){
  val noiseArticle=trendArticle(
   id="noise_test",
   title="10 Comments and 5 Replies on Share, Subscribe or Read More",
   body="He said it is about us in the news. Please like, follow, and leave comments below."
  )
  val extractedTerms=KeywordTrends.terms(noiseArticle)

  // Verify short <=2 letter noise words / pronouns are blocked across English and Portuguese
  for(shortWord in listOf("he", "it", "us", "in", "to", "at", "by", "of", "on", "or", "if", "is", "me", "my", "we", "do", "no", "so", "up", "da", "em", "se", "ou", "eu", "ha")){
   assertFalse("Short word '$shortWord' should be blocked", shortWord in extractedTerms)
  }

  // Verify UI boilerplate terms (case-insensitive) are filtered
  for(term in listOf("comments", "comment", "share", "reply", "replies", "read more", "subscribe", "subscribers", "like", "likes", "follow")){
   assertFalse("UI boilerplate term '$term' should be blocked", term in extractedTerms)
  }

  // Verify uppercase and lowercase tokens shorter than three characters are blocked
  val countryArticle=trendArticle("us_test", "Diplomacy Update", "They discussed US policy.")
  val countryTerms=KeywordTrends.terms(countryArticle)
  assertFalse("us" in countryTerms)
 }
}
