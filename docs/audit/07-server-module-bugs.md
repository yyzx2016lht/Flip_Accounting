# Server 模块 Bug 审计

**共 18 个发现**: 🔴 4 Critical | 🟠 7 High | 🟡 6 Medium | 🟢 1 Low

## 🔴 Critical

### 1. Full Stack Traces Leaked in Every Error Response

- **文件**: `server/src/main/java/org/ezbook/server/server/ServerApplication.kt`
- **行号**: 147
- **描述**: The buildErrorMessage() function unconditionally appends `cause.stackTraceToString()` to every error response body (line 147: `return baseMessage + "\n" + cause.stackTraceToString()`). The comment on line 131 mentions 'debug mode should attach full stack trace', but the code ALWAYS includes it regardless of the debugMode() check. The StatusPages handler at line 43 sends this to every client.
- **影响**: An attacker or any client can trigger any exception and receive the full JVM stack trace, revealing internal class names, package structure, file paths, line numbers, and library versions. This information is invaluable for crafting targeted attacks.
- **建议修复**: Only include the stack trace when `SettingUtils.debugMode()` returns true. Replace line 147 with a conditional: `return if (SettingUtils.debugMode()) baseMessage + "\n" + cause.stackTraceToString() else baseMessage`. Note: since buildErrorMessage is not suspend, the debug mode check must use runBlocking or the setting must be cached.

### 2. Remote Code Execution via /js/run Endpoint

- **文件**: `server/src/main/java/org/ezbook/server/server/JsRoutes.kt`
- **行号**: 29
- **描述**: The `POST /js/run` endpoint at line 29-32 accepts arbitrary JavaScript code from the request body and executes it via `service.executeJs(script)`. There is no authentication, no input validation, no sandboxing, and no restriction on what the JS can do.
- **影响**: Any local process can execute arbitrary JavaScript code on the server. While the server binds to localhost, any compromised app or process on the device can use this endpoint to execute arbitrary code, potentially accessing the database, reading/writing files, or making network requests.
- **建议修复**: Remove this endpoint entirely or restrict it to only execute pre-approved scripts. If needed for development, gate it behind debug mode AND add a secondary authentication token.

### 3. No Authentication on Any API Endpoint

- **文件**: `server/src/main/java/org/ezbook/server/server/ServerApplication.kt`
- **行号**: 52
- **描述**: The only access control is an IP-based check (lines 52-71) that allows all localhost connections and can be completely bypassed by setting debugMode to true via the unauthenticated `/setting/set` endpoint. There is no API key, no token, no session management, no authentication of any kind.
- **影响**: Any process running on the same device can access all API endpoints, including destructive ones like /db/clear, /db/import, /bill/clear. A malicious app could exfiltrate all financial data, modify bills, or destroy the database.
- **建议修复**: Implement a token-based authentication system. Generate a random token on first launch, store it securely, and require it in a header (e.g., Authorization) for all requests. The token should only be shared with the companion app through a secure channel.

### 4. Debug Mode Bypasses IP Restrictions via Unauthenticated Setting

- **文件**: `server/src/main/java/org/ezbook/server/server/ServerApplication.kt`
- **行号**: 53
- **描述**: Line 53: `if (SettingUtils.debugMode()) return@intercept` bypasses the entire IP check. Any client can enable debug mode by calling `POST /setting/set?key=debugMode&value=true` (no auth required), then access the server from any IP address.
- **影响**: Complete bypass of the only security control. An attacker on the network can enable debug mode and then access all endpoints from a remote IP.
- **建议修复**: Remove the debug mode bypass of IP restrictions. If debug mode needs network access for development, implement it through a separate, authenticated mechanism or only allow it in debug builds.

## 🟠 High

### 1. Destructive Operations via GET Requests

- **文件**: `server/src/main/java/org/ezbook/server/server/DataRoutes.kt`
- **行号**: 72
- **描述**: GET /data/clear (line 72) and GET /data/clearOld (line 83) perform destructive operations (deleting all data) via GET requests. GET requests can be triggered by link prefetching, browser history, crawlers, img tags, etc. Similarly, GET /db/export sends the full database file.
- **影响**: A malicious link or prefetch mechanism could accidentally trigger data deletion. The /db/export endpoint allows exfiltrating the entire database via a simple GET request link.
- **建议修复**: Change all destructive endpoints to require POST method with a CSRF token or confirmation parameter. Never use GET for operations with side effects.

### 2. Unauthenticated API Key Overwrite

- **文件**: `server/src/main/java/org/ezbook/server/server/AiApiRoutes.kt`
- **行号**: 39
- **描述**: The `applyIncomingSettings` function (lines 38-47) permanently writes the apiKey, apiUri, apiProvider, and model to the database when any AI endpoint is called. The POST /ai/request, POST /ai/models, and POST /ai/request/stream endpoints all call this. Combined with GET /ai/info which calls SettingUtils.setApiProvider (line 73), any client can redirect AI requests to a malicious server.
- **影响**: An attacker can: (1) redirect AI API calls to a phishing server to steal the API key, (2) change the AI model to a cheaper/less capable one, (3) exfiltrate user financial data by redirecting requests. The API key is stored in plaintext in the database.
- **建议修复**: Remove the ability to change API settings via request parameters. API key and provider configuration should only be changeable through a dedicated, authenticated settings UI. The /ai/info endpoint should not modify settings (line 73 calls setApiProvider).

### 3. Setting Routes Allow Overwriting Any Key Without Validation

- **文件**: `server/src/main/java/org/ezbook/server/server/SettingRoutes.kt`
- **行号**: 58
- **描述**: POST /setting/set accepts any key and any value with no authentication, no validation, and no restrictions. An attacker can set debugMode=true, change API keys, redirect API URIs, or create arbitrary settings. The key is taken from query parameters (line 59) and value from the raw request body (line 65).
- **影响**: Complete control over all application settings. An attacker can enable debug mode (bypassing IP restrictions), change AI provider credentials, redirect AI requests to a malicious server, or corrupt application behavior.
- **建议修复**: Add authentication, validate keys against a whitelist of allowed settings, and add rate limiting. Sensitive settings like API keys should require additional confirmation.

### 4. No Rate Limiting on Any Endpoint

- **文件**: `server/src/main/java/org/ezbook/server/server/ServerApplication.kt`
- **行号**: 35
- **描述**: No rate limiting is configured anywhere in the application. All endpoints are equally accessible without any throttling.
- **影响**: A malicious process can: (1) spam AI requests to exhaust API credits, (2) create unlimited analysis tasks to consume resources, (3) flood log/setting/bill endpoints to fill the database, (4) brute-force the /js/analysis endpoint to trigger expensive AI calls.
- **建议修复**: Implement rate limiting per IP or per session. At minimum, add rate limiting to expensive endpoints like /ai/request, /ai/request/stream, /js/analysis, and /analysis/create.

### 5. AI API Key Passed as URL Query Parameter (Gemini)

- **文件**: `server/src/main/java/org/ezbook/server/ai/providers/GeminiProvider.kt`
- **行号**: 33
- **描述**: Line 33: `val url = "${base()}?key=${getApiKey()}"` passes the Gemini API key as a URL query parameter. URL parameters are logged by web servers, proxies, CDNs, browser history, and network monitoring tools.
- **影响**: The Gemini API key can be intercepted from server logs, proxy logs, or network traffic. The key is also visible in the /ai/info endpoint if the model name leaks the provider.
- **建议修复**: Pass the API key in a request header instead of a URL parameter. Use the x-goog-api-key header (which is already used for the actual API call at line 90) consistently.

### 6. API Keys Stored in Plaintext in Database

- **文件**: `server/src/main/java/org/ezbook/server/tools/SettingUtils.kt`
- **行号**: 208
- **描述**: API keys are stored as plain strings in the SettingModel table via setApiKey/setRaw. No encryption, no key derivation, no secure storage is used. The database file is accessible to any process with root access or via the /db/export endpoint.
- **影响**: API keys for ChatGPT, DeepSeek, Gemini, and other providers can be extracted from the database backup (accessible via GET /db/export). These keys could be used by an attacker to make API calls at the user's expense.
- **建议修复**: Use Android Keystore or EncryptedSharedPreferences to encrypt sensitive values before storing them in the database. At minimum, encrypt API keys using a key derived from the device.

### 7. Unvalidated Database Import Allows Malicious SQLite Injection

- **文件**: `server/src/main/java/org/ezbook/server/server/DatabaseRoutes.kt`
- **行号**: 70
- **描述**: POST /db/import accepts any file upload and replaces the entire database. The only validation is that the file exists and has non-zero length (line 172 of Db.kt). No schema validation, no integrity checks, no malware scanning.
- **影响**: A malicious database file could: (1) contain crafted data that exploits Room/Gson deserialization, (2) inject malicious JavaScript into rule fields that gets executed by /js/analysis, (3) set debugMode=true or change API keys to redirect to attacker's server.
- **建议修复**: Validate the imported database: verify the schema matches expectations, check for integrity using PRAGMA integrity_check, sanitize all text fields, and re-validate all settings after import.

## 🟡 Medium

### 1. No Request Body Size Limits

- **文件**: `server/src/main/java/org/ezbook/server/server/ServerApplication.kt`
- **行号**: 35
- **描述**: No request body size limits are configured in the Ktor module. POST endpoints like /log/addBatch, /book/put, /assets/put, and /tag/batch accept arbitrarily large arrays. The /ai/request endpoints pass user input directly to AI providers.
- **影响**: A malicious client can send extremely large payloads to: (1) exhaust server memory (OOM), (2) fill the database with junk data, (3) send massive prompts to AI providers to rack up costs.
- **建议修复**: Configure Ktor's ContentNegotiation with size limits. Add validation in each route to limit array sizes and string lengths. For example, cap /log/addBatch at 1000 entries.

### 2. NumberFormatException from Unvalidated Query Parameters

- **文件**: `server/src/main/java/org/ezbook/server/server/BillRoutes.kt`
- **行号**: 61
- **描述**: Multiple routes use `?.toInt()` or `?.toLong()` on query parameters without catching NumberFormatException. Examples: BillRoutes.kt line 61 (year), line 63 (month), line 242-244 (monthly/stats), DataRoutes.kt lines 51-52 (page/limit), LogRoutes.kt lines 49-50 (page/limit).
- **影响**: Sending `?year=abc` to any of these endpoints triggers a NumberFormatException that is caught by the global StatusPages handler, which returns the full stack trace (see finding #1). This also enables reconnaissance by confirming the server type and internal structure.
- **建议修复**: Use `toIntOrNull()` with a fallback and return a 400 error for invalid input. Example: `val year = call.request.queryParameters["year"]?.toIntOrNull() ?: return@get call.respond(ResultModel.error(400, "Invalid year"))`

### 3. Tag Batch Endpoint Deletes All Tags Before Insert (Data Loss Risk)

- **文件**: `server/src/main/java/org/ezbook/server/server/TagRoutes.kt`
- **行号**: 177
- **描述**: POST /tag/batch (lines 177-199) first calls `deleteAll()` then `batchInsert()`. If the batch insert fails after deleteAll succeeds, all tags are lost permanently. The operations are not wrapped in a database transaction.
- **影响**: Complete loss of all user-defined tags if the insert fails due to disk full, invalid data, or other errors.
- **建议修复**: Wrap the delete and insert operations in a @Transaction in the DAO. Alternatively, use a temporary table approach: insert new data first, then swap tables atomically.

### 4. BillMerger Static Cache Not Thread-Safe

- **文件**: `server/src/main/java/org/ezbook/server/tools/BillMerger.kt`
- **行号**: 92
- **描述**: Line 92: `private var cachedAssets: List<AssetsModel>? = null` is a static mutable field with no synchronization. The `getKnownAssets()` function reads and writes this cache without any locking. If called from multiple coroutines simultaneously, the cache could be read while being written.
- **影响**: Potential data race: one coroutine could see a partially-written list reference. In practice, Kotlin/JVM reference writes are atomic, so this is unlikely to cause a crash, but it could lead to stale or inconsistent data being used for account merging decisions.
- **建议修复**: Use `@Volatile` annotation on cachedAssets, or better yet, use a thread-safe lazy initialization pattern or a ConcurrentHashMap-backed cache.

### 5. ServerLog Uses runBlocking Inside Coroutine Context

- **文件**: `server/src/main/java/org/ezbook/server/log/ServerLog.kt`
- **行号**: 26
- **描述**: Line 26: `override fun isDebugMode(): Boolean = runBlocking { SettingUtils.debugMode() }` uses runBlocking inside a function that is called from coroutine contexts. If called from a dispatcher with limited threads (like Dispatchers.Main or a fixed thread pool), this can cause a deadlock.
- **影响**: Potential deadlock when logging from a limited dispatcher. If all threads in the dispatcher are blocked waiting for the runBlocking call to complete, and the database operation needs a thread from the same dispatcher, the system hangs.
- **建议修复**: Make isDebugMode a suspend function, or cache the debug mode value and update it periodically. Alternatively, use `Dispatchers.IO` explicitly inside the runBlocking call.

### 6. Error Responses Reflect User Input Without Sanitization

- **文件**: `server/src/main/java/org/ezbook/server/server/BillRoutes.kt`
- **行号**: 151
- **描述**: Line 151: `return@post call.respond(ResultModel.error(400, "Invalid bill id: $id"))` reflects the user-provided id value directly in the error message. Similar patterns exist in other routes.
- **影响**: While this is JSON output (not HTML), reflected input in error messages can still be used for information gathering or, if the response is rendered in a WebView, potential XSS.
- **建议修复**: Use generic error messages that don't include user input: `ResultModel.error(400, "Invalid bill id")`

## 🟢 Low

### 1. GeminiProvider System Prompt Sent as User Role

- **文件**: `server/src/main/java/org/ezbook/server/ai/providers/GeminiProvider.kt`
- **行号**: 71
- **描述**: Lines 71-79: The GeminiProvider sends both the system prompt and user prompt as 'user' role messages. Gemini's API expects system instructions to be sent via a different field (systemInstruction), not as user role content.
- **影响**: The system prompt is not properly injected, which means the AI model may not follow the intended behavior constraints. This could lead to unexpected or unstructured outputs that fail JSON parsing.
- **建议修复**: Use Gemini's native system instruction field: add `"system_instruction": {"parts": [{"text": system}]}` to the request body, and send only the user content as a user-role message.

