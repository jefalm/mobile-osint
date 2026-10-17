package com.example.mobileosint

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes raw feed bytes into text using an explicit, platform-independent charset decision tree.
 *
 * Android's default charset is never consulted. Resolution order:
 * 1. Unicode BOM (authoritative)
 * 2. HTTP `Content-Type` charset
 * 3. XML declaration `encoding=`
 * 4. UTF-8 (explicit default)
 *
 * UTF-8 decoding is strict. When a server omits/misdeclares its charset and serves single-byte
 * text, Windows-1252 (a superset of ISO-8859-1) recovers accented Portuguese/Spanish characters
 * instead of emitting U+FFFD replacement diamonds. Double-encoded UTF-8 that was labelled as a
 * legacy charset is repaired when the round-trip is lossless.
 */
object FeedTextDecoder {
 private const val PROLOG_LIMIT=1024
 private val prologEncoding=Regex("""(?i)^\s*<\?xml\b[^>]*\bencoding\s*=\s*["']\s*([A-Za-z0-9._:\-]+)\s*["']""")
 private val contentTypeCharset=Regex("""(?i)(?:^|;)\s*charset\s*=\s*"?([^";\s]+)"?""")
 private val utf8=StandardCharsets.UTF_8
 private val utf16le=StandardCharsets.UTF_16LE
 private val utf16be=StandardCharsets.UTF_16BE
 private val latin1=StandardCharsets.ISO_8859_1
 private val windows1252=runCatching{Charset.forName("windows-1252")}.getOrDefault(latin1)
 private val mojibakeMarkers=listOf("\u00C3","\u00C2","\u00E2\u20AC","\uFFFD")

 /** Decodes [bytes] without ever falling back to the JVM/Android platform default charset. */
 fun decode(bytes:ByteArray, contentType:String?=null):String{
  val bom=bom(bytes)
  if(bom!=null){
   decodeStrict(bytes,bom.second,bom.first)?.let{return finish(it)}
  }
  val declared=charsetFromContentType(contentType) ?: charsetFromXmlDeclaration(bytes)
  if(declared!=null){
   decodeStrict(bytes,0,declared)?.let{return finish(it)}
  }
  decodeStrict(bytes,0,utf8)?.let{return finish(it)}
  decodeStrict(bytes,0,windows1252)?.let{return finish(it)}
  return finish(String(bytes,utf8))
 }

 /** Explicit UTF-8 entry point used by callers that already own raw bytes. */
 fun decodeUtf8(bytes:ByteArray):String=decodeStrict(bytes,0,utf8)?.let(::finish) ?: finish(String(bytes,utf8))

 private fun finish(value:String):String=repairMojibake(value.removePrefix("\uFEFF"))

 private fun bom(bytes:ByteArray):Pair<Charset,Int>?{
  if(bytes.size>=3&&bytes[0]==0xEF.toByte()&&bytes[1]==0xBB.toByte()&&bytes[2]==0xBF.toByte())return utf8 to 3
  if(bytes.size>=2&&bytes[0]==0xFF.toByte()&&bytes[1]==0xFE.toByte())return utf16le to 2
  if(bytes.size>=2&&bytes[0]==0xFE.toByte()&&bytes[1]==0xFF.toByte())return utf16be to 2
  return null
 }

 private fun charsetFromContentType(value:String?):Charset?{
  val name=contentTypeCharset.find(value?:"")?.groupValues?.getOrNull(1) ?: return null
  return charset(name)
 }

 private fun charsetFromXmlDeclaration(bytes:ByteArray):Charset?{
  if(bytes.isEmpty())return null
  // ISO-8859-1 is byte-transparent for the ASCII prolog, so it can read any single-byte or UTF-8 prolog.
  val prolog=String(bytes,0,minOf(bytes.size,PROLOG_LIMIT),latin1)
  val name=prologEncoding.find(prolog)?.groupValues?.getOrNull(1) ?: return null
  return charset(name)
 }

 private fun charset(name:String?):Charset?{
  val normalized=name?.trim()?.trim('"','\'')?.takeIf{it.isNotEmpty()} ?: return null
  if(normalized.equals("utf8",true))return utf8
  return runCatching{Charset.forName(normalized)}.getOrNull()
 }

 private fun decodeStrict(bytes:ByteArray,offset:Int,charset:Charset):String?{
  if(offset<0||offset>bytes.size)return null
  return runCatching{
   charset.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes,offset,bytes.size-offset))
    .toString()
  }.getOrNull()
 }

 private fun encodeStrict(value:String,charset:Charset):ByteArray?=runCatching{
  val buffer=charset.newEncoder()
   .onMalformedInput(CodingErrorAction.REPORT)
   .onUnmappableCharacter(CodingErrorAction.REPORT)
   .encode(java.nio.CharBuffer.wrap(value))
  ByteArray(buffer.remaining()).also{buffer.get(it)}
 }.getOrNull()

 /** Repairs UTF-8 bytes that were decoded as a single-byte charset (for example "NotÃ­cias"). */
 private fun repairMojibake(value:String):String{
  if(mojibakeMarkers.none{it in value})return value
  val reencoded=encodeStrict(value,windows1252) ?: return value
  val repaired=decodeStrict(reencoded,0,utf8) ?: return value
  return if(mojibakeScore(repaired)<mojibakeScore(value))repaired else value
 }

 private fun mojibakeScore(value:String)=mojibakeMarkers.sumOf{marker->value.windowed(marker.length,1).count{it==marker}}
}
