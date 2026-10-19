# Smart video discovery — guide

Discovery has two independent parts:

1. **Local similarity** — recommendations among processed lectures.
2. **Online discovery** — real search on YouTube, VK / VK Video and Rutube.

## Local similarity

For every lecture `TopicExtractionService` builds a profile from:

* title (media metadata when available);
* structured notes summary and headings;
* key topics and frequent keywords;
* the beginning of the transcript.

The profile is embedded with the existing local `EmbeddingProvider`
(Ollama `nomic-embed-text`), stored in `lecture_topic`, and compared with exact
cosine similarity. Results are stored in `lecture_similarity`.

* The current lecture is always excluded.
* Duplicate imports of the same video are excluded by canonical URL, provider
  external id and checksum.
* The score is the actual cosine similarity. The UI shows it as a percentage
  only because it is a calibrated cosine value, and labels the basis
  (`EMBEDDING_TOPIC_PROFILE` or `LEXICAL_KEYWORDS` when embeddings are
  unavailable).

API: `GET /api/discovery/similar/{lectureId}`.
UI: lecture card and `/discover?lectureId=…`.

## Online providers

| Provider id | Platform | Method | Requirements / limitations |
|---|---|---|---|
| `youtube` | YouTube | YouTube Data API v3, else `yt-dlp ytsearchN:` | API key optional; quota applies. yt-dlp search is a supported search mechanism, no HTML scraping. Downloading may be blocked by YouTube bot checks on some networks. |
| `vkontakte` | VK / VK Видео | VK API `video.search` | A user access token with video permission is required. Without it the provider is explicitly unavailable. |
| `rutube` | Rutube | public Rutube search JSON API | Public but not a formally versioned partner API; failures are returned explicitly. |

Credentials are stored encrypted per installation in `provider_credential`
(AES-256-GCM). Configure them in `/settings → Smart Video Discovery`.
Keys are never returned by an API or written to logs.

Provider status is available at `GET /api/discovery/providers` and on
`/discover`.

## Search flow

1. Build the topic profile and 1–3 short queries (Russian and English where the
   source provides them).
2. Remove URLs, e-mail addresses, phone numbers and long IDs from queries.
3. Query each enabled provider (or only the selected platform).
4. Cache the provider response in `video_discovery_cache`
   (provider + query + filters; TTL configurable, errors cached for a shorter
   period).
5. Normalize canonical URLs/provider IDs and deduplicate.
6. Exclude the source video and already imported lectures.
7. Filter by platform, language, duration, publication age and max results.
8. Rank with embedding cosine of the lecture topic vs. result title+description
   plus a capped keyword-overlap bonus.
9. Persist normalized results in `discovered_video`.

Online relevance is based on **title/description only** because the video is not
downloaded. The response and UI label this basis explicitly and never claim a
transcript-level comparison.

API: `POST /api/discovery/search`

```json
{
  "lectureId": "…",
  "query": "optional explicit query",
  "platform": "rutube",
  "language": "en",
  "minDurationSeconds": 120,
  "maxDurationSeconds": 3600,
  "publishedWithinDays": 365,
  "maxResults": 10
}
```

## One-click import

`POST /api/discovery/import`

```json
{"provider":"rutube","externalId":"…","url":"https://rutube.ru/video/…/",
 "quality":"720p","clean":false,"slides":false}
```

Import uses the existing `RemoteLectureService.createFromUrl` (URL allow-list,
SSRF checks, yt-dlp, resumable pipeline) and `LectureJobService.submit`. It does
not duplicate the download mechanism and does not download anything during
search. If the URL is already in the library, the existing lecture id is
returned with `created:false` and no new job is submitted.

UI: `/discover` → «Добавить в библиотеку».

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DISCOVERY_ENABLED` | `true` | master switch |
| `DISCOVERY_PROVIDERS` | `youtube,vkontakte,rutube` | enabled providers |
| `DISCOVERY_CACHE_TTL` | `24h` | successful search cache |
| `DISCOVERY_ERROR_CACHE_TTL` | `10m` | failed search cache |
| `DISCOVERY_MAX_RESULTS_PER_PROVIDER` | `15` | raw provider cap |
| `DISCOVERY_MAX_RESULTS` | `20` | final cap |
| `DISCOVERY_MAX_QUERIES` | `3` | generated queries |
| `DISCOVERY_SIMILARITY_TOP_K` | `8` | local recommendations |
| `DISCOVERY_MIN_SIMILARITY` | `0.25` | local threshold |
| `DISCOVERY_MIN_ONLINE_RELEVANCE` | `0.20` | online threshold |
| `DISCOVERY_KEYWORD_BONUS` | `0.10` | capped keyword bonus |
| `DISCOVERY_REQUEST_TIMEOUT` | `20s` | provider HTTP timeout |
| `DISCOVERY_MAX_RESPONSE_BYTES` | `2000000` | response size cap |

## Security

* Only provider base URLs from configuration are contacted; user input is a
  query string, never a URL.
* Redirects are disabled; plain HTTP is allowed only for loopback test doubles.
* No CAPTCHA, DRM, authorization or platform restriction bypass.
* Queries exclude personal-data-like tokens before they leave the application.
* Provider API keys live encrypted in the per-installation credential store.
