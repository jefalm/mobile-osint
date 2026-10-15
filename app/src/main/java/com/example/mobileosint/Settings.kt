package com.example.mobileosint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
data class Config(val model:String="z-ai/glm-5.3-flash",val fallbacks:String="",val embeddingModel:String="openai/text-embedding-3-small",val embeddings:Boolean=false,val days:Int=5,val articleCap:Int=1000,val topK:Int=12,val refreshConcurrency:Int=0,val contextTokens:Int=8192,val outputTokens:Int=1500)
class Settings(context:Context){
 private val prefs=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
 fun config()=Config(prefs.getString("model","z-ai/glm-5.3-flash")!!,prefs.getString("fallbacks","")!!,prefs.getString("embeddingModel","openai/text-embedding-3-small")!!,prefs.getBoolean("embeddings",false),prefs.getInt("days",5),prefs.getInt("cap",1000),prefs.getInt("topK",12),prefs.getInt("refreshConcurrency",0),prefs.getInt("contextTokens",8192),prefs.getInt("outputTokens",1500))
 fun save(c:Config){require(c.model.isNotBlank());require(c.embeddingModel.isNotBlank());prefs.edit().putString("model",c.model.trim()).putString("fallbacks",c.fallbacks).putString("embeddingModel",c.embeddingModel.trim()).putBoolean("embeddings",c.embeddings).putInt("days",c.days.coerceIn(1,30)).putInt("cap",c.articleCap.coerceIn(10,5000)).putInt("topK",c.topK.coerceIn(1,24)).putInt("refreshConcurrency",c.refreshConcurrency.coerceAtLeast(0)).putInt("contextTokens",c.contextTokens.coerceIn(4096,1048576)).putInt("outputTokens",c.outputTokens.coerceIn(256,4000)).apply()}
 private fun key():SecretKey{val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val existing=ks.getKey("osint-api",null);if(existing!=null)return existing as SecretKey
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("osint-api",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()}
 fun saveKey(value:String){if(value.isBlank()){prefs.edit().remove("secret").apply();return};val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val encrypted=cipher.doFinal(value.trim().toByteArray());prefs.edit().putString("secret",Base64.encodeToString(cipher.iv+encrypted,Base64.NO_WRAP)).apply()}
 fun apiKey():String{val raw=prefs.getString("secret",null)?:return "";val bytes=Base64.decode(raw,Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)}
}
