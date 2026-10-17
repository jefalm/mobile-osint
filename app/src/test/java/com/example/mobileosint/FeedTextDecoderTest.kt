package com.example.mobileosint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.Charset

class FeedTextDecoderTest {
 @Test fun defaultsToExplicitUtf8AndPreservesPortugueseAccents(){
  val text="Not\u00EDcias: s\u00E1bado, v\u00EDnculos pol\u00EDticos"
  val decoded=FeedTextDecoder.decode(text.toByteArray(Charsets.UTF_8))
  assertEquals(text,decoded)
  assertFalse("UTF-8 input must never produce diamonds: $decoded",decoded.contains('\uFFFD'))
 }

 @Test fun honorsXmlDeclaredIso88591(){
  val xml="""<?xml version="1.0" encoding="ISO-8859-1"?><title>s\u00E1bado e v\u00EDnculos pol\u00EDticos</title>"""
  val decoded=FeedTextDecoder.decode(xml.toByteArray(Charset.forName("ISO-8859-1")),"application/rss+xml")
  assertEquals(xml,decoded)
  assertFalse(decoded.contains('\uFFFD'))
 }

 @Test fun honorsHttpContentTypeCharset(){
  val text="<title>pol\u00EDticos no s\u00E1bado</title>"
  val decoded=FeedTextDecoder.decode(text.toByteArray(Charset.forName("windows-1252")),"application/xml; charset=windows-1252")
  assertEquals(text,decoded)
  assertFalse(decoded.contains('\uFFFD'))
 }

 @Test fun recoversUndeclaredSingleByteFeedInsteadOfEmittingDiamonds(){
  val text="<title>s\u00E1bado \u00E0s 10h: v\u00EDnculos pol\u00EDticos</title>"
  val decoded=FeedTextDecoder.decode(text.toByteArray(Charset.forName("windows-1252")))
  assertEquals(text,decoded)
  assertFalse("Legacy single-byte text must be recovered without U+FFFD: $decoded",decoded.contains('\uFFFD'))
 }

 @Test fun repairsUtf8BytesMislabeledAsIso88591(){
  val xml="""<?xml version="1.0" encoding="ISO-8859-1"?><title>Not\u00EDcias</title>"""
  val decoded=FeedTextDecoder.decode(xml.toByteArray(Charsets.UTF_8),"application/xml; charset=ISO-8859-1")
  assertEquals(xml,decoded)
 }

 @Test fun stripsUtf8Bom(){
  val bom=byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())
  val text="<title>a</title>"
  assertEquals(text,FeedTextDecoder.decode(bom+text.toByteArray(Charsets.UTF_8)))
 }

 @Test fun honorsUtf16Bom(){
  val bom=byteArrayOf(0xFF.toByte(),0xFE.toByte())
  val text="<title>s\u00E1bado</title>"
  assertEquals(text,FeedTextDecoder.decode(bom+text.toByteArray(Charsets.UTF_16LE)))
 }
}
