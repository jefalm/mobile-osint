# Mobile OSINT: Android Kotlin experimental MVP

A multi-file Android Studio project with real RSS/Atom ingestion, Room persistence, local BM25 evidence filtering, optional feed-sync embeddings, streamed OpenRouter answers, and offline evidence-backed reports. No mocked articles or fake vectors are included.

## Validation status: read this first

This is an experimental source project, not a production-certified application. On October 12, 2026, 52 offline JVM tests passed and the debug APK built with JBR 17. A read-only frozen phone snapshot was compared using the saved pre-change report and the new selector on identical 33/69 article windows (15/20 publisher hosts); supporting URLs resolved to stored article IDs in both runs. The old top eight were `russia`, `trump`, `sao paulo`, `russo`, `mesmo`, `russian`, `inicio`, `eu`; the new top eight before phrase-fragment consolidation were `sao paulo`, `energy ceasefire`, `estados unidos`, `saudi arabia`, `russia`, `trump`, `us`, `russian`. For example, the five `sao paulo` supporting article IDs were the same in both runs, and `energy ceasefire` had two distinct supporting article IDs. This is indexed-feed coverage, not a verified web-wide trend. The final APK was installed in place and a fresh local report rendered 35/73 articles, 9/58 stale feeds and a consolidated `Luiz Inácio Lula` card with six current articles; `pode` and `contra` were absent from its top eight. The original first-install timestamp was preserved; the final SQLite snapshot retained schema 4, integrity `ok`, 58 sources and 1000 articles (reports grew from 49 to 52 through three local checks). Model output still needs human review; these checks do not certify production readiness. The original generation-environment limitations recorded in VALIDATION.json predate this local verification.

## Open and run

1. Extract the ZIP into a normal local folder, for example `C:\Android\MobileOsint`.
2. In Android Studio, choose **File > Open** and select the `MobileOsint` folder containing `settings.gradle.kts`. Do not create a new Empty Activity project or copy individual files.
3. Allow Gradle synchronization. Use a JDK 17-compatible Gradle runtime. Install Android SDK Platform 35 and Build Tools 35.0.0 through SDK Manager if requested. Internet access is required for dependencies.
4. Connect a phone running Android 8.0/API 26 or newer. Enable Developer options and USB debugging, and accept the computer's debugging authorization on the phone. Select the `app` run configuration and your phone, then click Run.
5. In **Settings**, enter your own OpenRouter key. The default chat routing is `openrouter/auto`; you can replace it with a model ID available to your account. Optional fallback IDs are comma-separated. Save settings.
6. In **Sources**, paste an HTTPS RSS/Atom URL you are authorized to use. Tap **Validate & add** to save it without importing articles. Tap **Sync now** to ingest; hourly refresh is also scheduled.
7. Confirm a SUCCESS status and a nonzero article count. The default retention is the last five rolling days. A valid feed can yield zero articles when all its dated items are older than the window.
8. In **Research**, ask a question using terms present in the ingested material, ask for the latest news, or ask "what are the keywords trending" / "quais palavras-chave estão em alta?". **Filters** can restrict dates, feeds, publisher hosts, literal article topics and languages, and set a keyword window of 15-1440 minutes (default 60). Portuguese, English and unknown are included by default. Tap **Analyze**. Reports and evidence are saved automatically. **Stop** cancels cloud generation and preserves partial output.
9. Open **Reports** to read saved results offline. New keyword reports group counts and supporting article headlines into expandable keyword cards; opening a headline follows its source link. Windows, refreshes and article times display in Brasília time, and each expanded card shows its counting key and source excerpt. The shared warning appears once above the cards and diagnostics are under **Details**. Older keyword reports remain readable in their original text format. Source deletion removes live data but not report snapshots.

### PowerShell build and test

From the extracted project directory, with Java and the Android SDK configured:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK output is `app\build\outputs\apk\debug\app-debug.apk`.

If Java is not found, set `JAVA_HOME` to an installed JBR 17 (the Android Studio bundled JBR may be newer):

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-17.0.14"
.\gradlew.bat --bootstrap-info
.\gradlew.bat testDebugUnitTest assembleDebug
```

If command-line Gradle cannot find the SDK, open/sync in Android Studio first so it creates `local.properties`, or set `ANDROID_HOME` to your actual SDK directory. Do not commit machine-specific SDK paths.

## Gradle wrapper

The project uses the standard Gradle wrapper scripts and wrapper JAR. The Gradle 9.6.0 distribution is pinned in `gradle/wrapper/gradle-wrapper.properties`; it is downloaded from Gradle on first command-line use. The wrapper requires Java 17 or newer. The older bootstrap source under `tools/bootstrap-src` is not used by the current wrapper.

## Implemented behavior

- Compose screens: Research, Sources, Reports, Settings.
- Source validation using a bounded GET and RSS/Atom parsing; HTTPS only; 2 MB feed limit; feed title extraction.
- Separate fetch-enabled and include-in-research switches; source deletion confirmation; health/failure counters; last successful sync timestamp.
- Unique hourly periodic WorkManager job and unique manual job. A shared in-process mutex prevents their ingestion runs from overlapping. Fetch/parse uses a dynamic queue with configurable concurrency; set `MAX_CONCURRENT_FEED_FETCHES` or the app setting to a positive integer, or leave the value at `0` to process all selected feeds concurrently. Individual feed failures are recorded while other feeds continue; WorkManager can retry failed sync runs. Serialized transactional updates, completed/total progress and duration/failure diagnostics remain in place. Android scheduling is inexact.
- Source existence/enabled/revision checks inside ingestion transactions prevent stale fetches from recreating deleted/disabled sources.
- Per-source stable identity deduplication; changed content replaces chunks and invalidates embeddings. Articles from different feeds are stored independently; duplicate retrieved chunk text is suppressed. Syndicated copies are not automatically classified as independent corroboration.
- Five-day default rolling UTC retention, configurable 1-30 days; default 1000 articles, configurable 10-5000. Cleanup runs on process startup and sync, and after settings changes. Retrieval also applies the age filter.
- Immutable first-seen fallback for undated items, retained in a bounded 50,000-entry identity ledger. Ledger entries survive article eviction but are removed when the source is deleted. If the ledger cap evicts an identity, an undated item may be treated as newly seen later. Publication age cannot be established for undated items.
- Unlimited sources are supported; up to 1000 parsed items per feed; body capped at 16,000 characters; title at 300; up to 16 chunks/article. Chunks use 1600 characters with 200-character overlap, NOT exact token-based segmentation. Existing clipped bodies regain additional text only on a normal re-fetch while it is still present in the feed; changed bodies replace old chunks/vectors. Unchanged existing bodies do not require re-chunking. Embedding remains limited to 100 pending chunks per sync.
- Room applies bound research-source, UTC time-window, source, optional publisher-host (resolved to source IDs), language and literal topic filters before returning at most 4000 recent chunks. Research does not make a network request for labeling, translation, embeddings or retrieval, even when feed-sync embeddings are enabled. Portuguese, English and uncertain/unknown-language articles are included by default; the question language never automatically excludes another language. Source tags are editable hints for ranking only, not proof that every article matches a topic.
- Local BM25 uses accent normalization, common Portuguese/English stopwords and a small explicit bilingual synonym list for a few topics; identifiers such as CVE IDs, IPs and domains remain intact. Multi-term matching favors title coverage and declines weak scattered keyword matches. This is not a general translator or cross-language semantic search. A restrictive literal topic may miss inflections, synonyms outside the small vocabulary or articles that discuss a topic without spelling it out. A low-confidence match ends before an OpenRouter request with guidance to broaden or specify terms.
- Portuguese and English latest-news requests select recent unique articles from research-enabled feeds; broad news requests do not require generic words to match feed text. Topical requests retain BM25 topic relevance before recency ordering. Shorter excerpts admit more news items within the same context budget. This is indexed feed coverage, not live web search.
- Keyword-trend questions use a separate, offline article-level calculation (no API key or OpenRouter call). Language-aware Portuguese/English (combined for unknown language) stopwords and feed-clutter filtering keep grammatical filler out of the candidate pool; original-case short acronyms and named phrases retain their context. Contiguous one- to three-word candidates from cleaned titles and short body openings are deduplicated per article. Candidate scores combine smoothed document-frequency IDF, a small coverage term for corpora under five articles, and a phrase-association weight; supported candidates from at least two articles and two publishers exclude singletons when available. Overlapping phrase fragments with nearly identical current and preceding support are consolidated; components with independent article coverage remain eligible. These are heuristics, not semantic understanding: a recurring generic term or syndicated report may still rank. Publisher counts use distinct known feed hosts. A configurable recent window is compared with an equal preceding window, displaying article/publisher counts, undated-item counts, feed freshness, conservative increase/no-trend labels and supporting article links. Sparse baselines, missing publication dates, retention-limited history and stale sources block a verified increase; this measures indexed coverage, not general news popularity.
- Optional OpenRouter feed-sync embeddings remain stored but are not used to rank research queries; no query embedding is sent. The existing embedding sync limit is 100 pending chunks per run.
- Publisher-host and feed diversity are counted separately when a host is known. Source-diverse evidence with overlap suppression and configurable top-K 1-24 keeps differing accounts from distinct articles. Stored article context may expand a retrieved excerpt. An estimated character-based context budget reserves instruction and output space; auto/fallback routing is capped at 8192 estimated context tokens. Reports retain applied filters, candidate and match counts, reasons for selected articles, publisher hosts and an estimated prompt size. Estimates are not exact token counts and model output/citations still require review.
- Indexing processes at most 100 pending chunks per sync, in batches of 16. Changing embedding model causes incremental re-indexing on subsequent syncs. Old-model vectors are not mixed with the new model. Repeat sync to finish a backlog.
- If local matching or the bound filters find no suitable article, no chat-generation request is made; the UI explains whether filters or lexical confidence caused the empty result.
- Evidence is copied into an independent snapshot before generation, with URL, title, source, publication or first-seen time, retrieval time, and content hash. Undated articles are explicitly labeled as first seen, never as published. Latest-news responses also receive article/source coverage and last successful feed refresh.
- Explicit chat model, optional ordered model fallbacks, or `openrouter/auto`; actual returned model saved with each report.
- SSE framing handles comments, multiline data fields, [DONE], and error payloads. Cancellation cancels the HTTP call. Connection/read/call timeouts and response-size guards are configured.
- UI publishes accumulated text on a 50 ms ticker, with final flush. Running output is checkpointed approximately every second. A process restart marks interrupted RUNNING reports PARTIAL.
- COMPLETE, PARTIAL, FAILED, and TRUNCATED statuses. A token-limit finish with partial answer text is TRUNCATED; one with no answer text is FAILED with output-budget guidance. Requests ask for low reasoning effort to leave room for an answer, but model support varies. Provider HTTP/SSE failures remain separate from empty indexed results.
- Up to 100 retained reports with their evidence. Reports are NOT permanent. Older non-running reports are automatically evicted; individual deletion is available.
- API key encrypted using AES-GCM with an Android Keystore key; only ciphertext is persisted; backups disabled. The plaintext credential necessarily exists briefly in app memory during authentication. No API key is bundled or logged.

## Scope and limitations

- This is a public-feed research MVP, not a general web crawler, private-account collector, threat detector, or trend-detection system.
- It uses the text supplied by feeds, which may be summaries or full content. It does not fetch linked full articles, infer missing publication dates, or guarantee complete source coverage.
- It does not include local LLM inference, on-device embedding models, provider-policy controls, model-catalog discovery, a byte-exact total database budget, or background report generation.
- The 4000-chunk candidate cap means older eligible chunks may not participate in a query even when they remain in the database. Source diversity selection can return fewer than top-K chunks.
- Prompt size is bounded by evidence count/character limits and question length, not by a model-specific tokenizer. Choose a model with a sufficient context window; API errors are surfaced.
- SQLite deleted pages are reused; automatic physical file compaction is not implemented. No promise is made that the file shrinks after every purge.
- Generation cannot resume from the same interrupted remote stream. A new analysis is a new billable request.
- Source editing/URL replacement is not included; delete/re-add a feed to change its URL. The UI is intentionally simple and English-only; answers follow the question language.
- Prompt-injection instructions and citations are safeguards, not guarantees. Model output and citations require human verification. Reports are not verified intelligence.
- Feed content and report evidence are not encrypted at rest by this app; device storage protections apply. Do not ingest confidential workplace data without authorization.
- Only add trusted public HTTPS feeds. Comprehensive SSRF/private-network restrictions and publisher-specific legal/licensing controls are not implemented. Respect source permissions and terms.
- Cloud embeddings and generation can incur charges. Auto routing and fallback models can change the billed model. Use provider-side spend limits.

## Acceptance checklist on your phone

- Build the project and run the JVM tests.
- Add a real feed with recent items; verify SUCCESS and article count.
- Sync twice; verify unchanged items are not duplicated.
- Disable fetching; verify subsequent sync skips the source.
- Disable research eligibility; verify its evidence is excluded from new queries.
- Generate a report; confirm actual model and evidence snapshots in Reports.
- Cancel a stream; verify PARTIAL, not COMPLETE.
- Delete a source; verify live articles disappear and saved evidence remains.
- Lower retention/article cap; verify cleanup and retrieval exclusion.
- Enable embeddings and sync; confirm research retrieval remains local and no query embedding is sent.
- Restart offline; verify saved reports/evidence can be read.

## Files

- `Database.kt`: Room entities, relationships, DAO.
- `Settings.kt`: configuration and Keystore-backed credential encryption.
- `RssProvider.kt`: feed parsing/normalization.
- `Network.kt`: cancellable HTTP, embedding provider, SSE, prompt assembly.
- `Algorithms.kt`: chunking, hashing, BM25, cosine, RRF.
- `ResearchService.kt`: ingestion, retention, retrieval, snapshots, generation.
- `OsintApplication.kt`: dependency construction and WorkManager scheduling.
- `OsintViewModel.kt`: reactive screen state and UI actions.
- `MainActivity.kt`: Compose UI.
- `AlgorithmsTest.kt`: eight JVM unit tests (included, not executed here).

## Reference documentation

- Android Gradle Plugin 8.9 compatibility: https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes
- Android physical-device setup: https://developer.android.com/studio/run/device
- Room: https://developer.android.com/jetpack/androidx/releases/room
- OpenRouter embeddings: https://openrouter.ai/docs/api_reference/embeddings
- OpenRouter streaming: https://openrouter.ai/docs/api/reference/streaming
