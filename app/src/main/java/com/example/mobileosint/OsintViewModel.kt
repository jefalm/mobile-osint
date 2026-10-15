package com.example.mobileosint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId
data class UiState(val busy:Boolean=false,val text:String="",val notice:String="",val reportId:String?=null,val evidence:List<EvidenceEntity> = emptyList(),val diagnostics:String="",val trendReport:KeywordReport?=null)
class OsintViewModel(app:Application):AndroidViewModel(app){
 val service=(app as OsintApplication).service
 val sources=service.dao.sources().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val reports=service.dao.reports().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val count=service.dao.count().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
 val progress=service.progress
 val syncRuns=service.dao.recentSyncRuns(30).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val latestSyncRun=service.dao.latestSyncRunFlow().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
 val activeFeedFailures=service.dao.activeFeedFailures().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 suspend fun getFeedSyncLogs(runId:String)=service.dao.feedLogsForRun(runId)
 private val _importState=MutableStateFlow(FeedImportState());val importState=_importState.asStateFlow()
 private val _ui=MutableStateFlow(UiState());val ui=_ui.asStateFlow();private var job:Job?=null
 fun action(block:suspend ()->Unit){viewModelScope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){_ui.update{it.copy(notice=e.message?:"Operation failed")}}}}
 fun add(url:String)=action{service.addSource(url)}
 fun importFeeds(urls:String){if(_importState.value.running)return;_importState.value=FeedImportState(running=true);action{
  try{service.importFeeds(urls){_importState.value=it}}catch(e:CancellationException){throw e}catch(e:Exception){
   _importState.update{it.copy(running=false,failures=it.failures+(e.message?:"Import failed"))}
  }
 }}
 fun clearImportResult(){if(!_importState.value.running)_importState.value=FeedImportState()}
 fun toggle(s:SourceEntity,fetch:Boolean?=null,research:Boolean?=null)=action{service.toggle(s.id,fetch,research)}
 fun setTags(s:SourceEntity,tags:String)=action{service.setTags(s.id,tags)}
 fun setAllFeedsEnabled(enabled:Boolean)=action{service.setAllFeedsEnabled(enabled)}
 fun delete(s:SourceEntity)=action{service.deleteSource(s.id)}
 fun sync(){enqueueSync(getApplication());_ui.update{it.copy(notice="Sync queued; see Sources for progress")}}
 fun save(c:Config,key:String)=action{withContext(Dispatchers.IO){service.settings.save(c);if(key.isNotBlank())service.settings.saveKey(key)};service.cleanup();_ui.update{it.copy(notice="Settings saved")}}
 fun clearKey()=action{withContext(Dispatchers.IO){service.settings.saveKey("")};_ui.update{it.copy(notice="API key removed")}}
 fun clearNotice(){_ui.update{it.copy(notice="")}}
 fun ask(q:String,filters:ResearchFilters=ResearchFilters(),fromDate:String="",toDate:String=""){if(_ui.value.busy)return;_ui.value=UiState(busy=true);job=viewModelScope.launch{
    try{
     val zone=ZoneId.of("America/Sao_Paulo")
     val from=fromDate.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli()}
     val to=toDate.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()-1}
    val id=service.research(q,{text->_ui.update{it.copy(text=text)}},filters.copy(fromAt=from,toAt=to));val e=service.dao.evidence(id);val report=service.dao.report(id)
    val trend=report?.let{KeywordReportCodec.decode(it.text)}
      _ui.update{it.copy(reportId=id,evidence=e,diagnostics=report?.error?:"",trendReport=trend,text=if(trend!=null)"" else report?.text?:it.text,notice=if(report?.requestedModel=="local/keyword-trends") "Local keyword coverage saved; counts and links below." else if(report?.status=="COMPLETE")"Report saved. ${report.error.substringBefore(" | filters:")}" else "${report?.status?:"FAILED"}: ${report?.error?.substringAfter(" | finish_reason:",report.error)?.take(250)?:"Generation failed"}")}
    }
  catch(e:CancellationException){_ui.update{it.copy(notice="Cancelled; partial report retained")};throw e}
  catch(e:java.time.format.DateTimeParseException){_ui.update{it.copy(notice="Dates must use YYYY-MM-DD.")}}
  catch(e:Exception){_ui.update{it.copy(notice=e.message?:"Research failed")}}
  finally{_ui.update{it.copy(busy=false)}}
 }}
 fun cancel(){job?.cancel()}
 fun open(r:ReportEntity)=action{val e=service.dao.evidence(r.id);val trend=KeywordReportCodec.decode(r.text);_ui.value=UiState(text=if(trend==null)r.text else "",notice="${r.status} | ${r.actualModel.ifBlank{r.requestedModel}}",reportId=r.id,evidence=e,diagnostics=r.error,trendReport=trend)}
 fun deleteReport(r:ReportEntity)=action{service.dao.deleteReport(r.id)}
}
