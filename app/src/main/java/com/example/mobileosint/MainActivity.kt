package com.example.mobileosint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.URI
import java.util.Locale

object TrendDisplay {
 private val dateTime=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm",Locale.ROOT).withZone(ZoneId.of("America/Sao_Paulo"))
 fun clock(at:Long)=dateTime.format(Instant.ofEpochMilli(at))
 fun time(at:Long)="${clock(at)} Brasília (America/Sao_Paulo)"
 fun articleUrl(url:String):String?=runCatching{URI(url).takeIf{it.scheme in listOf("https","http")&&it.host!=null}?.toString()}.getOrNull()
 fun articleTime(article:TrendSupport)=if(article.publishedAt!=null)"Published ${time(article.publishedAt)}" else "First seen ${time(article.firstSeenAt)}; publication time unknown"
}

@Composable fun KeywordOverview(report:KeywordReport){
 Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  Text("Keyword coverage in indexed feeds",style=MaterialTheme.typography.titleMedium)
  Text("Times in Brasília (America/Sao_Paulo)",style=MaterialTheme.typography.bodySmall)
  Text("Current: ${TrendDisplay.clock(report.start)} to ${TrendDisplay.clock(report.end)} · ${report.currentArticles} articles / ${report.currentPublishers} hosts · ${report.currentUndated} undated")
  Text("Previous equal window: ${TrendDisplay.clock(report.previousStart)} to ${TrendDisplay.clock(report.start)} · ${report.previousArticles} articles / ${report.previousPublishers} hosts · ${report.previousUndated} undated")
  Text("Feed freshness: ${report.staleFeeds}/${report.sourceCount} not refreshed this window · Last success: ${report.lastRefresh?.takeIf{it>0}?.let{TrendDisplay.clock(it)}?:"never"}",style=MaterialTheme.typography.bodySmall)
  if(report.staleFeeds>0)Text("Some feeds are stale; apparent increases may reflect ingestion timing. Keyword-specific coverage appears below.",style=MaterialTheme.typography.bodySmall)
  if(report.baselineSparse)Text("Sparse preceding window; no comparable trend can be established.",style=MaterialTheme.typography.bodySmall)
  if(report.keywords.isEmpty())Text("No indexed articles in this window.")
 }
}

@Composable fun KeywordCard(keyword:TrendKeyword,enabled:Boolean,onTopicClick:(String)->Unit){
 val context=LocalContext.current
 var expanded by remember(keyword.key){mutableStateOf(false)}
 var visible by remember(keyword.key){mutableIntStateOf(6)}
 Card(Modifier.fillMaxWidth()){
  Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
  TextButton(onClick={onTopicClick(keyword.label)},enabled=enabled,contentPadding=PaddingValues(0.dp)){Text(keyword.label,style=MaterialTheme.typography.titleMedium)}
   Text("Now ${keyword.currentArticles} articles / ${keyword.currentPublishers} hosts · Before ${keyword.previousArticles} articles / ${keyword.previousPublishers} hosts",style=MaterialTheme.typography.bodyMedium)
   Text(keyword.status,style=MaterialTheme.typography.bodySmall)
  TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide articles" else "Show supporting articles (${keyword.supports.size}${if(keyword.currentArticles>keyword.supports.size)" of ${keyword.currentArticles}" else ""})")}
   if(expanded){
    Text("Counting key: ${keyword.key}",style=MaterialTheme.typography.labelSmall)
    keyword.supports.take(visible).forEach{article->
     HorizontalDivider()
     val url=TrendDisplay.articleUrl(article.url)
     if(url!=null)TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))},contentPadding=PaddingValues(0.dp)){Text(article.title)} else Text(article.title,style=MaterialTheme.typography.titleSmall)
     Text("${article.publisher} · ${TrendDisplay.articleTime(article)}",style=MaterialTheme.typography.bodySmall)
     SelectionContainer{Text("Excerpt: ${article.excerpt}",style=MaterialTheme.typography.bodySmall)}
    }
    if(visible<keyword.supports.size)TextButton(onClick={visible+=6}){Text("Show more (${keyword.supports.size-visible} remaining)")}
   }
  }
 }
}

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=android.graphics.Color.BLACK;WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars=false;setContent{MaterialTheme{Surface(Modifier.fillMaxSize()){OsintApp()}}}}}
private data class SelectionMenuState(val rect:Rect,val onCopy:(()->Unit)?,val onPaste:(()->Unit)?,val onCut:(()->Unit)?,val onSelectAll:(()->Unit)?)
private class SearchTextToolbar:TextToolbar{
 var menu by mutableStateOf<SelectionMenuState?>(null)
 override val status get()=if(menu==null)TextToolbarStatus.Hidden else TextToolbarStatus.Shown
 override fun showMenu(rect:Rect,onCopyRequested:(()->Unit)?,onPasteRequested:(()->Unit)?,onCutRequested:(()->Unit)?,onSelectAllRequested:(()->Unit)?){menu=SelectionMenuState(rect,onCopyRequested,onPasteRequested,onCutRequested,onSelectAllRequested)}
 override fun hide(){menu=null}
}
private class SelectionMenuPositionProvider(private val rect:Rect):PopupPositionProvider{
 override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset{
  val x=((rect.left+rect.right-popupContentSize.width)/2).toInt().coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0))
  val above=(rect.top-popupContentSize.height).toInt()
  val y=if(above>=0)above else rect.bottom.toInt().coerceAtMost((windowSize.height-popupContentSize.height).coerceAtLeast(0))
  return IntOffset(x,y)
 }
}
@Composable private fun SearchSelectionToolbar(toolbar:SearchTextToolbar){
 val menu=toolbar.menu?:return
 val context=LocalContext.current
 Popup(popupPositionProvider=remember(menu.rect){SelectionMenuPositionProvider(menu.rect)},onDismissRequest={},properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false)){
  Surface(shape=MaterialTheme.shapes.small,shadowElevation=8.dp,color=MaterialTheme.colorScheme.inverseSurface){
   Row(verticalAlignment=Alignment.CenterVertically){
    menu.onCut?.let{TextButton(onClick={it();toolbar.hide()},contentPadding=PaddingValues(horizontal=8.dp)){Text("Cut",color=MaterialTheme.colorScheme.inverseOnSurface)}}
    menu.onCopy?.let{TextButton(onClick={it();toolbar.hide()},contentPadding=PaddingValues(horizontal=8.dp)){Text("Copy",color=MaterialTheme.colorScheme.inverseOnSurface)}}
    menu.onPaste?.let{TextButton(onClick={it();toolbar.hide()},contentPadding=PaddingValues(horizontal=8.dp)){Text("Paste",color=MaterialTheme.colorScheme.inverseOnSurface)}}
    menu.onSelectAll?.let{TextButton(onClick={it();toolbar.hide()},contentPadding=PaddingValues(horizontal=8.dp)){Text("Select all",color=MaterialTheme.colorScheme.inverseOnSurface)}}
    menu.onCopy?.let{copy->TextButton(onClick={
     copy()
     val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
     val selected=clipboard.primaryClip?.takeIf{it.itemCount>0}?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
     if(!selected.isNullOrEmpty())context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q=${Uri.encode(selected)}")))
     toolbar.hide()
    },contentPadding=PaddingValues(horizontal=8.dp)){Text("Search",color=MaterialTheme.colorScheme.inverseOnSurface)}}
   }
  }
 }
}
@Composable fun OsintApp(vm:OsintViewModel=viewModel()){
 var tab by remember{mutableIntStateOf(0)};val ui by vm.ui.collectAsStateWithLifecycle();val snackbarHostState=remember{SnackbarHostState()}
 val searchToolbar=remember{SearchTextToolbar()}
 LaunchedEffect(ui.notice){if(ui.notice.isNotBlank()){snackbarHostState.showSnackbar(ui.notice);vm.clearNotice()}}
 CompositionLocalProvider(LocalTextToolbar provides searchToolbar){
 Box(Modifier.fillMaxSize()){
  if(Build.VERSION.SDK_INT>=35)Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).align(Alignment.TopCenter).background(androidx.compose.ui.graphics.Color.Black))
  Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
  Column(Modifier.fillMaxSize()){
   Text("Mobile OSINT",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(16.dp))
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){listOf("Research","Sources","Reports","Settings").forEachIndexed{i,label->TextButton(onClick={tab=i},enabled=!ui.busy||i==0){Text(label)}}}
   Box(Modifier.weight(1f)){when(tab){0->ResearchScreen(vm);1->SourcesScreen(vm);2->ReportsScreen(vm){tab=0};else->SettingsScreen(vm)}}
  }
  SnackbarHost(snackbarHostState,Modifier.align(Alignment.BottomCenter).padding(16.dp))
  }
 }
 SearchSelectionToolbar(searchToolbar)
 }
}
@Composable fun ResearchScreen(vm:OsintViewModel){
 val ui by vm.ui.collectAsStateWithLifecycle();var query by remember{mutableStateOf("")}
 val listState=rememberLazyListState();var controlsVisible by remember{mutableStateOf(true)};var questionFocused by remember{mutableStateOf(false)}
 var accumulatedScroll by remember{mutableFloatStateOf(0f)};val threshold=with(LocalDensity.current){16.dp.toPx()}
 val scrollConnection=remember(listState,questionFocused,threshold){object:NestedScrollConnection{
  override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset{
   if(questionFocused){controlsVisible=true;accumulatedScroll=0f}
   else if(listState.firstVisibleItemIndex==0&&listState.firstVisibleItemScrollOffset==0){controlsVisible=true;accumulatedScroll=0f}
   else{
    if(consumed.y<0f){
     accumulatedScroll+=consumed.y
     if(accumulatedScroll<=-threshold){controlsVisible=false;accumulatedScroll=0f}
    }else accumulatedScroll=0f
   }
   return Offset.Zero
  }
 }}
 LaunchedEffect(listState){snapshotFlow{listState.firstVisibleItemIndex==0&&listState.firstVisibleItemScrollOffset==0}.collect{atTop->if(atTop){controlsVisible=true;accumulatedScroll=0f}}}
 val context=LocalContext.current
 val speechLauncher=rememberLauncherForActivityResult(contract=ActivityResultContracts.StartActivityForResult()){result->
  if(result.resultCode==Activity.RESULT_OK){
   val spokenText=result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
   if(!spokenText.isNullOrBlank()){
    query=spokenText
   }
  }
 }
 val sources by vm.sources.collectAsStateWithLifecycle()
 var showFilters by remember{mutableStateOf(false)};var filterView by remember{mutableIntStateOf(0)}
 var selectedSources by remember{mutableStateOf(setOf<String>())};var selectedPublishers by remember{mutableStateOf(setOf<String>())}
 var selectedLanguages by remember{mutableStateOf(setOf("pt","en","und"))};var topic by remember{mutableStateOf("")}
 val selectedLanguageMode=ResearchLanguageMode.fromCodes(selectedLanguages)
 var fromDate by remember{mutableStateOf("")};var toDate by remember{mutableStateOf("")}
 var trendMinutes by remember{mutableStateOf("60")}
 val publishers=sources.mapNotNull{LocalRetriever.publisher(it.url)}.distinct().sorted()
 fun submit(question:String)=vm.ask(question,ResearchFilters(sourceIds=selectedSources,publishers=selectedPublishers,topic=topic,languages=selectedLanguages,trendMinutes=trendMinutes.toIntOrNull()?:0),fromDate,toDate)
 Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  AnimatedVisibility(visible=controlsVisible||questionFocused,enter=expandVertically(expandFrom=Alignment.Top)+fadeIn(),exit=shrinkVertically(shrinkTowards=Alignment.Top)+fadeOut()){
   Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
    OutlinedTextField(
     query,
     {query=it.take(2000)},
     label={Text("Question about indexed feeds")},
     trailingIcon={
      IconButton(
       onClick={
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
         putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
         putExtra(RecognizerIntent.EXTRA_LANGUAGE,selectedLanguageMode.speechLocale)
         putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,selectedLanguageMode.speechLocale)
         putExtra(RecognizerIntent.EXTRA_PROMPT,selectedLanguageMode.speechUiLabel)
        }
        runCatching{speechLauncher.launch(intent)}.onFailure{
         Toast.makeText(context,"Speech recognition unavailable",Toast.LENGTH_SHORT).show()
        }
       },
       enabled=!ui.busy
      ){
       Icon(Icons.Filled.Mic,contentDescription=selectedLanguageMode.speechUiLabel)
      }
     },
     modifier=Modifier.fillMaxWidth().onFocusChanged{questionFocused=it.isFocused;if(it.isFocused){controlsVisible=true;accumulatedScroll=0f}},
     enabled=!ui.busy
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
     ResearchLanguageMode.entries.forEachIndexed{index,mode->SegmentedButton(
      selected=selectedLanguageMode==mode,
      onClick={selectedLanguages=mode.languageCodes},
      shape=SegmentedButtonDefaults.itemShape(index,ResearchLanguageMode.entries.size)
     ){Text(mode.label)} }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
     Button(onClick={submit(query)},enabled=query.isNotBlank()&&!ui.busy,modifier=Modifier.weight(1f)){Text("Analyze")}
      Button(onClick={submit("latest news")},enabled=!ui.busy,modifier=Modifier.weight(1f)){Text("Latest news",modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)}
      Button(onClick={submit("what are the keywords trending")},enabled=!ui.busy,modifier=Modifier.weight(1f)){Text("Trending topics",modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)}
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={showFilters=true},enabled=!ui.busy){Text("Filters")}}
    Text("${if(selectedSources.isEmpty())"All feeds" else "${selectedSources.size} feeds"} | ${if(selectedPublishers.isEmpty())"All publishers" else "${selectedPublishers.size} publishers"} | ${selectedLanguageMode.label} ${topic.takeIf{it.isNotBlank()}?.let{"| $it"}?:""}",style=MaterialTheme.typography.bodySmall)
   }
  }
  if(ui.busy)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
   LinearProgressIndicator(Modifier.weight(1f))
   TextButton(onClick={vm.cancel()}){Text("Stop")}
  }
  LazyColumn(Modifier.weight(1f).nestedScroll(scrollConnection),state=listState,verticalArrangement=Arrangement.spacedBy(12.dp)){
    val trend=ui.trendReport
    if(trend!=null){
     item{KeywordOverview(trend)}
    items(trend.keywords,key={it.key}){keyword->KeywordCard(keyword,!ui.busy){label->query=label;submit(label)}}
     item{var expanded by remember(trend.end){mutableStateOf(false)};TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide details" else "Details")};if(expanded)SelectionContainer{Text("${trend.filters}\n${ui.diagnostics}",style=MaterialTheme.typography.bodySmall)}}
    }else{
    item{
     if(ui.text.isNotBlank()&&!ui.busy){
      Column(Modifier.fillMaxWidth()){
       Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
      IconButton(onClick={
       val sources=ui.evidence.sortedBy{it.citation}.joinToString("\n\n"){evidence->
        "[${evidence.citation}] ${evidence.title}\n${evidence.source}\n${evidence.url}\n${if(evidence.publishedAt!=null)"Published: ${TrendDisplay.time(evidence.publishedAt)}" else "Published: unknown | First seen: ${evidence.firstSeenAt?.let{TrendDisplay.time(it)}?:"unknown"}"}"
       }
       val copiedText=buildString{
        append(ui.text.trim())
        if(sources.isNotBlank())append("\n\nSources\n\n").append(sources)
       }
       val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
       clipboard.setPrimaryClip(ClipData.newPlainText("Research answer",copiedText))
       Toast.makeText(context,"Answer and sources copied",Toast.LENGTH_SHORT).show()
      }){Icon(Icons.Filled.ContentCopy,contentDescription="Copy answer and sources")}
       }
       SelectionContainer{Markdown(ui.text,modifier=Modifier.fillMaxWidth())}
      }
     }else SelectionContainer{Text(ui.text.ifBlank{if(ui.reportId!=null)"No answer text was returned. Check the status above and the saved evidence below." else "Add an HTTPS RSS/Atom feed in Sources and sync it. Keyword coverage is calculated locally; other answers use selected evidence and OpenRouter."})}
    }
     if(ui.diagnostics.isNotBlank())item{Text(ui.diagnostics,style=MaterialTheme.typography.bodySmall)}
     items(ui.evidence,key={it.id}){e->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
    Text("[${e.citation}] ${e.title}",style=MaterialTheme.typography.titleSmall)
     SelectionContainer{Text("${e.source}\n${e.url}\n${if(e.publishedAt!=null) "Published: ${TrendDisplay.time(e.publishedAt)}" else "Published: unknown | First seen: ${e.firstSeenAt?.let{TrendDisplay.time(it)}?:"unknown"}"}\n${e.text}")}
    if(e.url.startsWith("https://")||e.url.startsWith("http://"))TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(e.url)))}){Text("Open article")}
   }}}
    }
  }
 }
 if(showFilters)AlertDialog(onDismissRequest={showFilters=false},title={Text("Evidence filters")},text={Column(Modifier.heightIn(max=460.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Row{listOf("Options","Publishers","Feeds").forEachIndexed{index,label->TextButton(onClick={filterView=index}){Text(label)}}}
  Column(Modifier.verticalScroll(rememberScrollState())){when(filterView){
   0->{
    OutlinedTextField(topic,{topic=it.take(80)},label={Text("Article topic (literal keyword or phrase)")},modifier=Modifier.fillMaxWidth())
    Text("A source tag is only a hint. Topic filters match article text; multilingual aliases cover a small vocabulary.",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(trendMinutes,{trendMinutes=it.take(4)},label={Text("Keyword trend window (minutes, 15-1440)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(fromDate,{fromDate=it.take(10)},label={Text("From date (YYYY-MM-DD)")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(toDate,{toDate=it.take(10)},label={Text("Through date (YYYY-MM-DD)")},modifier=Modifier.fillMaxWidth())
   }
   1->{Text("No selection includes all publishers.");publishers.forEach{host->Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(host in selectedPublishers,{selectedPublishers=if(it)selectedPublishers+host else selectedPublishers-host});Text(host)}}}
   else->{Text("No selection includes all research-enabled feeds.");sources.forEach{source->Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(source.id in selectedSources,{selectedSources=if(it)selectedSources+source.id else selectedSources-source.id});Text(source.title)}}}
  }}}
 },confirmButton={TextButton(onClick={vm.clearNotice();showFilters=false}){Text("Done")}},dismissButton={TextButton(onClick={selectedSources=emptySet();selectedPublishers=emptySet();selectedLanguages=setOf("pt","en","und");topic="";fromDate="";toDate="";trendMinutes="60";vm.clearNotice()}){Text("Clear")}})
}
@Composable fun SourcesScreen(vm:OsintViewModel){
 val sources by vm.sources.collectAsStateWithLifecycle();val count by vm.count.collectAsStateWithLifecycle();val progress by vm.progress.collectAsStateWithLifecycle()
 val context=LocalContext.current
 var url by remember{mutableStateOf("")};var deleting by remember{mutableStateOf<SourceEntity?>(null)}
 var tagging by remember{mutableStateOf<SourceEntity?>(null)};var tagText by remember{mutableStateOf("")}
 var showImport by remember{mutableStateOf(false)};var importUrls by remember{mutableStateOf("")}
 val importState by vm.importState.collectAsStateWithLifecycle()
 Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("$count stored articles | $progress")
  OutlinedTextField(url,{url=it},label={Text("HTTPS RSS/Atom feed URL")},modifier=Modifier.fillMaxWidth(),singleLine=true)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   Button(onClick={vm.add(url)},enabled=url.isNotBlank()){Text("Validate & add")}
   OutlinedButton(onClick={vm.sync()}){Text("Sync now")}
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedButton(onClick={vm.clearImportResult();showImport=true},modifier=Modifier.weight(1f)){Text("Import feeds")}
   OutlinedButton(onClick={
    val links=sources.joinToString("\n"){it.url}
    val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("RSS feed URLs",links))
    Toast.makeText(context,"Copied ${sources.size} feed links",Toast.LENGTH_SHORT).show()
   },enabled=sources.isNotEmpty(),modifier=Modifier.weight(1f)){Text("Copy links")}
  }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
     OutlinedButton(onClick={vm.setAllFeedsEnabled(true)},modifier=Modifier.weight(1f)){Text("Enable all feeds")}
     OutlinedButton(onClick={vm.setAllFeedsEnabled(false)},modifier=Modifier.weight(1f)){Text("Disable all feeds")}
    }
  Text("Fetch and research eligibility are separate. Retention still applies when fetching is disabled.",style=MaterialTheme.typography.bodySmall)
  LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
   items(sources,key={it.id}){s->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
    Text(s.title,style=MaterialTheme.typography.titleMedium);Text(s.url,style=MaterialTheme.typography.bodySmall)
    Text("${s.status} | failures: ${s.failures}\nLast success: ${if(s.lastSuccess==0L)"never" else TrendDisplay.time(s.lastSuccess)}",style=MaterialTheme.typography.bodySmall)
    Text("Tags: ${s.tags.ifBlank{"none"}}",style=MaterialTheme.typography.bodySmall)
    Row{Text("Fetch",Modifier.weight(1f));Switch(s.enabled,{vm.toggle(s,fetch=it)})}
    Row{Text("Include in research",Modifier.weight(1f));Switch(s.research,{vm.toggle(s,research=it)})}
    TextButton(onClick={tagging=s;tagText=s.tags}){Text("Edit tags")}
    TextButton(onClick={deleting=s}){Text("Delete source & live articles")}
   }}}
  }
 }
 if(showImport)AlertDialog(onDismissRequest={if(!importState.running)showImport=false},title={Text("Import feeds")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedTextField(importUrls,{importUrls=it},label={Text("One HTTPS feed URL per line")},minLines=5,maxLines=9,enabled=!importState.running,modifier=Modifier.fillMaxWidth())
  if(importState.running||importState.total>0){
   Text("${importState.completed}/${importState.total} checked | Added: ${importState.added} | Skipped: ${importState.skipped} | Failed: ${importState.failed}")
   if(importState.running&&importState.total>0)LinearProgressIndicator(progress={importState.completed.toFloat()/importState.total},modifier=Modifier.fillMaxWidth())
  }
  importState.failures.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
 }},confirmButton={TextButton(onClick={vm.importFeeds(importUrls)},enabled=!importState.running&&importUrls.isNotBlank()){Text("Import")}},dismissButton={TextButton(onClick={showImport=false},enabled=!importState.running){Text("Close")}})
 tagging?.let{s->AlertDialog(onDismissRequest={tagging=null},title={Text("Source tags")},text={OutlinedTextField(tagText,{tagText=it.take(200)},label={Text("Comma-separated hints")})},confirmButton={TextButton(onClick={vm.setTags(s,tagText);tagging=null}){Text("Save")}},dismissButton={TextButton(onClick={tagging=null}){Text("Cancel")}})}
 deleting?.let{s->AlertDialog(onDismissRequest={deleting=null},title={Text("Delete source?")},text={Text("Live articles, chunks and vectors will be removed. Saved report evidence remains.")},confirmButton={TextButton(onClick={vm.delete(s);deleting=null}){Text("Delete")}},dismissButton={TextButton(onClick={deleting=null}){Text("Cancel")}})}
}
@Composable fun ReportsScreen(vm:OsintViewModel,onOpen:()->Unit){
 val reports by vm.reports.collectAsStateWithLifecycle()
 LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  item{Text("Saved offline reports (up to 100). Evidence snapshots survive source deletion and feed retention.")}
  items(reports,key={it.id}){r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
   Text(r.question,style=MaterialTheme.typography.titleMedium)
  Text("${r.status} | ${TrendDisplay.time(r.createdAt)}\nModel: ${r.actualModel.ifBlank{r.requestedModel}}")
  if(r.error.isNotBlank()){var details by remember(r.id){mutableStateOf(false)};if(r.requestedModel!="local/keyword-trends"||details||r.status!="COMPLETE")Text(if(details||r.status!="COMPLETE")r.error else r.error.substringBefore(" | filters:"),style=MaterialTheme.typography.bodySmall);TextButton(onClick={details=!details}){Text(if(details)"Hide diagnostics" else "Diagnostics")}}
   Row{TextButton(onClick={vm.open(r);onOpen()}){Text("Open")};TextButton(onClick={vm.deleteReport(r)},enabled=r.status!="RUNNING"){Text("Delete")}}
  }}}
 }
}
@Composable fun SettingsScreen(vm:OsintViewModel){
 val initial=remember{vm.service.settings.config()};var model by remember{mutableStateOf(initial.model)};var fallback by remember{mutableStateOf(initial.fallbacks)}
 var key by remember{mutableStateOf("")};var embedding by remember{mutableStateOf(initial.embeddingModel)};var enabled by remember{mutableStateOf(initial.embeddings)}
 var days by remember{mutableStateOf(initial.days.toString())};var cap by remember{mutableStateOf(initial.articleCap.toString())};var topK by remember{mutableStateOf(initial.topK.toString())}
 var refreshLimit by remember{mutableStateOf(initial.refreshConcurrency.toString())};var contextLimit by remember{mutableStateOf(initial.contextTokens.toString())};var outputLimit by remember{mutableStateOf(initial.outputTokens.toString())}
 LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Text("Cloud calls can incur charges. Your question and selected feed excerpts are sent to OpenRouter and its providers. Embeddings send feed chunks too. Use only material you are authorized to process.")}
  item{OutlinedTextField(key,{key=it},label={Text("API key (blank keeps existing key)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())}
  item{OutlinedTextField(model,{model=it},label={Text("Chat model ID or openrouter/auto")},modifier=Modifier.fillMaxWidth())}
  item{OutlinedTextField(fallback,{fallback=it},label={Text("Fallback model IDs, comma separated")},modifier=Modifier.fillMaxWidth())}
  item{Row{Text("Enable cloud embeddings",Modifier.weight(1f));Switch(enabled,{enabled=it})}}
  item{OutlinedTextField(embedding,{embedding=it},label={Text("Embedding model ID")},modifier=Modifier.fillMaxWidth())}
  item{Text("Research filtering always uses local BM25 without query embeddings. Cloud embeddings, if enabled, are only generated during feed sync (up to 100 pending chunks per run).")}
  item{OutlinedTextField(days,{days=it},label={Text("Retention days (1-30)")},modifier=Modifier.fillMaxWidth())}
  item{OutlinedTextField(cap,{cap=it},label={Text("Maximum articles (10-5000)")},modifier=Modifier.fillMaxWidth())}
  item{OutlinedTextField(refreshLimit,{refreshLimit=it},label={Text("Concurrent feed fetches (1-100)")},modifier=Modifier.fillMaxWidth())}
  item{OutlinedTextField(topK,{topK=it},label={Text("Maximum evidence articles (1-24)")},modifier=Modifier.fillMaxWidth())}
  item{OutlinedTextField(contextLimit,{contextLimit=it},label={Text("Model context budget in tokens (4096-1048576)")},modifier=Modifier.fillMaxWidth())}
  item{OutlinedTextField(outputLimit,{outputLimit=it},label={Text("Reserved output tokens (256-4000)")},modifier=Modifier.fillMaxWidth())}
  item{Text("Auto routing or fallback models use at most an 8192-token context budget. Input counts are character-based estimates, not model-tokenizer counts.",style=MaterialTheme.typography.bodySmall)}
  item{Button(onClick={vm.save(Config(model,fallback,embedding,enabled,days.toIntOrNull()?:initial.days,cap.toIntOrNull()?:initial.articleCap,topK.toIntOrNull()?:initial.topK,refreshLimit.toIntOrNull()?:initial.refreshConcurrency,contextLimit.toIntOrNull()?:initial.contextTokens,outputLimit.toIntOrNull()?:initial.outputTokens),key);key=""}){Text("Save settings")}}
  item{TextButton(onClick={vm.clearKey()}){Text("Remove saved API key")}}
 }
}
