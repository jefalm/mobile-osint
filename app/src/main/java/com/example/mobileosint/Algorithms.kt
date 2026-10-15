package com.example.mobileosint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.*
object Algorithms {
 private val stopwords=("a an and are as at be by for from how in is it of on or the to what which with about latest recent news updates do does happened show find list tell me " +
  "um uma o os as e em de da das dos no na nos nas por para que qual quais como sao sobre com mais ultimas ultima recentes recente noticias novidades aconteceu acontecimentos mostre quero saber").split(Regex("\\s+")).filter{it.isNotBlank()}.map{tokens(it).single()}.toSet()
 private val synonyms=mapOf("clima" to "climate","climate" to "climate","economia" to "economy","economy" to "economy","economico" to "economy","economic" to "economy","saude" to "health","health" to "health","eleicao" to "election","eleicoes" to "election","election" to "election","elections" to "election","tecnologia" to "technology","technology" to "technology","energia" to "energy","energy" to "energy","preco" to "price","precos" to "price","price" to "price","prices" to "price","guerra" to "war","war" to "war","mudanca" to "change","mudancas" to "change","change" to "change","changes" to "change")
 private val portugueseMarkers=setOf("uma","sao","sobre","noticias","para","com","mais","que","dos","das","pela","pelo","hoje","apos","seu","sua","como","entre")
 private val englishMarkers=setOf("the","and","with","from","news","after","that","this","their","which","about","today","between","will","have")
 fun cutoff(now:Long,days:Int)=now-days.coerceIn(1,30)*86_400_000L
 fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
 fun chunks(s:String,size:Int=1600,overlap:Int=200):List<String>{
  require(size>overlap && overlap>=0)
  if(s.length<=size)return listOf(s)
  val out=mutableListOf<String>();var start=0
  val bounds=listOf("\n\n","\n",". ","? ","! ","; ")
    while(start<s.length && out.size<16){
   val endCandidate=min(start+size,s.length)
   if(endCandidate==s.length){out.add(s.substring(start));break}
   var cutIndex=-1;val window=s.substring(start,endCandidate)
   val minCut=max(0,window.length-400)
   for(delim in bounds){val pos=window.lastIndexOf(delim);if(pos>=minCut){cutIndex=start+pos+delim.length;break}}
   if(cutIndex<0){val spacePos=window.lastIndexOf(' ');if(spacePos>=minCut)cutIndex=start+spacePos+1}
   if(cutIndex<=start)cutIndex=endCandidate
   out.add(s.substring(start,cutIndex).trim())
   val nextStart=cutIndex-overlap
   start=if(nextStart>start)nextStart else cutIndex
  }
  return out.filter{it.isNotBlank()}
 }
 fun tokens(s:String)=Regex("[\\p{L}\\p{N}]+(?:[._:/-][\\p{L}\\p{N}]+)*").findAll(Normalizer.normalize(s.lowercase(),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")).map{it.value}.toList()
 fun searchTokens(s:String)=tokens(s).filter{it !in stopwords}.map{synonyms[it]?:it}
 fun aliases(term:String):List<String>{val canonical=synonyms[term]?:term;return synonyms.filterValues{it==canonical}.keys.toList().ifEmpty{listOf(term)}}
 fun detectLanguage(s:String):String{
  val words=tokens(s.take(1200));val pt=words.count{it in portugueseMarkers};val en=words.count{it in englishMarkers}
  return when{pt>=2&&pt>en*2->"pt";en>=2&&en>pt*2->"en";else->"und"}
 }
 fun keywordTrendIntent(question:String):Boolean{
  val words=tokens(question).joinToString(" ")
  val keywords=Regex("\\b(keywords?|key terms?|palavras?[ -]chave|termos?[ -]chave|termos?|top terms?|assuntos?|topics?)\\b").containsMatchIn(words)
  val trends=Regex("\\b(trend(?:ing|s)?|em alta|tendencias?|mais (?:citados?|frequentes?))\\b").containsMatchIn(words)
  return keywords&&trends
 }
 fun latestTopic(question:String):String?{
  val words=tokens(question).joinToString(" ")
  if(!Regex("\\b(ultimas?|recentes?|latest|newest|breaking)\\b").containsMatchIn(words))return null
  val news=Regex("\\b(noticias|news|manchetes|headlines|novidades|updates)\\b").find(words)?:return null
  return words.substring(news.range.last+1).replace(Regex("\\b(mais|ultimas?|recentes?|latest|newest|breaking)\\b"),"").trim()
   .replace(Regex("^(sobre|de|do|da|dos|das|about|on|in|regarding)\\s+"),"").trim()
 }
 fun bm25Scores(query:String,docs:List<String>):DoubleArray{if(docs.isEmpty())return DoubleArray(0);val terms=searchTokens(query).distinct();val bags=docs.map{searchTokens(it)};val avg=bags.map{it.size}.average().coerceAtLeast(1.0);val scores=DoubleArray(docs.size)
  for(term in terms){val df=bags.count{term in it};if(df==0)continue;val idf=ln(1+(docs.size-df+0.5)/(df+0.5));bags.forEachIndexed{i,bag->val tf=bag.count{it==term}.toDouble();scores[i]+=idf*(tf*2.2)/(tf+1.2*(0.25+0.75*bag.size/avg))}}
  return scores}
 fun bm25(query:String,docs:List<String>):List<Int>{val scores=bm25Scores(query,docs);return scores.indices.filter{scores[it]>0}.sortedByDescending{scores[it]}}
 fun rrf(a:List<Int>,b:List<Int>):List<Int>{val scores=mutableMapOf<Int,Double>();for(list in listOf(a,b))list.forEachIndexed{rank,id->scores[id]=(scores[id]?:0.0)+1.0/(60+rank+1)};return scores.keys.sortedWith(compareByDescending<Int>{scores[it]}.thenBy{it})}
 fun encode(v:FloatArray):ByteArray=ByteBuffer.allocate(v.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{v.forEach{putFloat(it)}}.array()
 fun decode(b:ByteArray):FloatArray{require(b.size%4==0);val buffer=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);return FloatArray(b.size/4){buffer.float}}
 fun cosine(a:FloatArray,b:FloatArray):Double{if(a.size!=b.size)return -1.0;var dot=0.0;var aa=0.0;var bb=0.0;for(i in a.indices){dot+=a[i].toDouble()*b[i];aa+=a[i].toDouble()*a[i];bb+=b[i].toDouble()*b[i]};return if(aa==0.0||bb==0.0)0.0 else dot/sqrt(aa*bb)}
}
