package com.example.mobileosint
import android.text.Html
import java.io.StringReader
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class FeedItem(val identity:String,val title:String,val url:String,val text:String,val publishedAt:Long?,val contentType:String="UNKNOWN")
data class Feed(val title:String,val items:List<FeedItem>)
class RssProvider{
 suspend fun fetch(url:String)=parse(Network.get(url),url)
 fun parse(xml:String,feedUrl:String):Feed{
  val dbf=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply{
   isNamespaceAware=true
   runCatching{isXIncludeAware=false}
   runCatching{isExpandEntityReferences=false}
   runCatching{setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true)}
   runCatching{setFeature("http://xml.org/sax/features/external-general-entities",false)}
   runCatching{setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
   runCatching{setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)}
  }
  val builder=dbf.newDocumentBuilder()
  val doc=builder.parse(org.xml.sax.InputSource(StringReader(xml)))
  val feedTitle=runCatching{
   val titles=doc.getElementsByTagName("title")
   if(titles.length>0) titles.item(0).textContent.trim().take(200) else ""
  }.getOrDefault("")
  
  val items=mutableListOf<FeedItem>()
  val list=mutableListOf<org.w3c.dom.Node>()
  val tagNames=listOf("item","entry")
  for(tag in tagNames){
   val nodes=doc.getElementsByTagName(tag)
   for(i in 0 until nodes.length) list.add(nodes.item(i))
   val nodesNs=doc.getElementsByTagNameNS("*",tag)
   for(i in 0 until nodesNs.length){
    val node=nodesNs.item(i)
    if(!list.contains(node)) list.add(node)
   }
  }

  for(node in list){
   if(node.nodeType!=org.w3c.dom.Node.ELEMENT_NODE) continue
   val elem=node as org.w3c.dom.Element
   var title=""; var link=""; var id=""; var desc=""; var full=""; var meta=""; var dateStr=""
   
   val children=elem.childNodes
   for(i in 0 until children.length){
    val child=children.item(i)
    if(child.nodeType!=org.w3c.dom.Node.ELEMENT_NODE) continue
    val tag=child.nodeName.lowercase()
    val localTag=child.localName?.lowercase()?:tag
    val textVal=child.textContent.trim()
    when{
     tag=="title"||localTag=="title"->title=textVal
     tag=="link"||localTag=="link"->{
      val href=(child as org.w3c.dom.Element).getAttribute("href")
      val rel=child.getAttribute("rel")
      if(href.isNotBlank()){
       if(rel.isBlank()||rel=="alternate") link=href
      } else if(link.isBlank()) link=textVal
     }
     tag=="guid"||localTag=="guid"||tag=="id"||localTag=="id"->id=textVal
     tag=="content"||localTag=="content"||tag=="content:encoded"||localTag=="encoded"->if(textVal.length>full.length) full=textVal
     tag=="description"||localTag=="description"||tag=="summary"||localTag=="summary"->if(textVal.length>desc.length) desc=textVal
     tag=="category"||localTag=="category"||tag=="rights"||localTag=="rights"->meta+=" "+textVal.lowercase()
     tag=="pubdate"||localTag=="pubdate"||tag=="published"||localTag=="published"||tag=="date"||localTag=="date"->if(dateStr.isBlank()) dateStr=textVal
     tag=="updated"||localTag=="updated"->if(dateStr.isBlank()) dateStr=textVal
    }
   }
   
   val rawBody=full.ifBlank{desc}
   val cleanText=runCatching{
    Html.fromHtml(rawBody,Html.FROM_HTML_MODE_LEGACY).toString()
   }.getOrElse{
    rawBody.replace(Regex("<[^>]*>"),"")
   }.replace(Regex("\\s+")," ").trim()
   
    val text=cleanText.take(16000)
   val absolute=runCatching{java.net.URI(feedUrl).resolve(link).toString()}.getOrDefault(feedUrl)
   val paywallKeywords=listOf("exclusivo para assinantes","subscriber-only","subscribers only","conteúdo exclusivo","paywall")
   val hasPaywall=paywallKeywords.any{meta.contains(it)||rawBody.lowercase().contains(it)}
   val contentType=when{
    hasPaywall->"PAYWALL_INDICATED"
    cleanText.length>16000->"CLIPPED"
    full.isNotBlank()->"FEED_CONTENT"
    desc.isNotBlank()->"SUMMARY"
    else->"UNKNOWN"
   }
   
   val date=if(dateStr.isNotBlank()) parseDate(dateStr) else null
   if(title.isNotBlank()&&text.isNotBlank()&&items.size<1000){
    items.add(FeedItem(id.ifBlank{absolute.ifBlank{title}},title.take(300),absolute,text,date,contentType))
   }
  }
  
  val host=runCatching{java.net.URI(feedUrl).host}.getOrNull()?:"Feed"
  return Feed(feedTitle.ifBlank{host},items)
 }
 private fun parseDate(s:String):Long?{
  for(f in listOf(DateTimeFormatter.RFC_1123_DATE_TIME,DateTimeFormatter.ISO_OFFSET_DATE_TIME,DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z",Locale.US))){try{return ZonedDateTime.parse(s.trim(),f).toInstant().toEpochMilli()}catch(_:Exception){}}
  return runCatching{Instant.parse(s.trim()).toEpochMilli()}.getOrNull()
 }
}
