package com.example.mobileosint
import android.app.Application
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
class OsintApplication:Application(){
 lateinit var service:ResearchService;private set
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 override fun onCreate(){super.onCreate();service=ResearchService(AppDatabase.create(this),Settings(this))
  scope.launch{service.initialize()}
  WorkManager.getInstance(this).enqueueUniquePeriodicWork("rss-periodic",ExistingPeriodicWorkPolicy.KEEP,
   PeriodicWorkRequestBuilder<IngestionWorker>(1,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
 }
}
class IngestionWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
 override suspend fun doWork():Result=try{val s=(applicationContext as OsintApplication).service;if(s.sync())Result.success()else if(runAttemptCount<3)Result.retry()else Result.failure()}
 catch(e:CancellationException){throw e}catch(_:Exception){if(runAttemptCount<3)Result.retry()else Result.failure()}
}
fun enqueueSync(context:Context){WorkManager.getInstance(context).enqueueUniqueWork("rss-manual",ExistingWorkPolicy.KEEP,
 OneTimeWorkRequestBuilder<IngestionWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}
