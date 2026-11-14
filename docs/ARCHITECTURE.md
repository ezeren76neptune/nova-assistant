# Architecture

Nova is a single Android app (`app.nova.assistant`). Everything hangs off `NovaApp`, which creates the long-lived parts once:

```
NovaApp
 ├─ settings      core/Settings.kt          typed SharedPreferences (every option is a K.* key)
 ├─ memory        core/MemoryStore.kt       facts, notes, conversation log
 ├─ vosk          speech/VoskModelManager   downloads/loads the offline speech model
 ├─ voiceId       speech/VoiceId            speaker model, voiceprint, verification
 ├─ wake          speech/WakeWordEngine     always-on wake word (own thread + AudioRecord)
 ├─ llm           llm/LlmManager            loads/unloads the model, single-thread inference
 ├─ home          home/HomeAssistant        HA REST client, entity cache, matching
 ├─ tools         tools/ToolRegistry        every tool, enable/disable, execution
 ├─ agent         agent/Agent               quick commands → LLM tool loop
 ├─ speaker/audio speech/Speaker, AudioFx   text-to-speech, earcons, audio focus
 ├─ popup         ui/PopupPresenter         overlay or activity popup
 └─ controller    core/AssistantController  the state machine everything reports to
```

## The request lifecycle

`AssistantController` owns the UI state (`StateFlow<UiState>`) and moves between phases:

`IDLE → LISTENING → THINKING → SPEAKING → READY → (LISTENING again | IDLE)`

1. **Wake.** `ListenerService` (a foreground microphone service) runs `WakeWordEngine`.
   - The engine keeps a 3-second ring buffer of audio. On a match, it asks `VoiceId.verify()` (when enabled) and calls `controller.onWakeWord()`.
   - Anything that needs the microphone ("hold reasons": the assistant listening, TTS speaking, Voice ID enrollment, the call/battery/screen rules) pauses the engine with `wake.hold(reason)`. It releases the mic before the next user takes it.
2. **Listen.** `Recognizers.create()` picks Android's `SpeechRecognizer` (explicitly a non-Nova service, because Nova itself is a recognition service when it's the default assistant), the on-device recognizer, or `VoskRecognizer`. It falls back down that list on errors.
3. **Think.** `Agent.handle()` checks, in order:
   - a pending confirmation (yes/no);
   - a pending question ("What should the message say?");
   - `QuickCommands.parse()`, a large deterministic parser for common phrasings, which can also resolve "it" from the last tool used;
   - the LLM loop.
4. **LLM loop.** `Prompt.system()` lists the enabled tools as `name(params) - description`, a few examples, the user's facts, and the Home Assistant devices.
   - The model must answer with a single JSON object; `ReplyParser` is lenient about code fences, `<think>` blocks, alternate key names and truncated JSON.
   - Tool results that need explaining go back to the model as `TOOL_RESULT …` messages.
   - Things that happened outside the model (quick commands, confirmations) go into `Conversation.carryOver` and are prepended to the next user message. This keeps the message list append-only, so LiteRT-LM's KV cache can be reused (`LiteRtLmEngine` compares message hashes).
5. **Speak / finish.** The controller adds the turn, speaks it unless the result is `quiet` (e.g. music started), and then:
   - listens again if Nova asked a question;
   - closes if the tool said `dismiss`;
   - otherwise waits for auto-dismiss.

   Closing the popup, or the popup disappearing for any reason, cancels everything and stops all sound.

## Tools

A tool is `Tool(name, params, description, category, status) { args -> ToolOutcome }`, running in a coroutine on the main thread.

`ToolOutcome` can carry:
- `confirm`: a `Pending` with a question and an action;
- `ask`: a follow-up question plus a continuation;
- `needsModel`: let the LLM summarize `data`;
- `dismiss` and `quiet`.

`ToolContext.launch()` starts activities from the accessibility service's context first. System-bound services are exempt from background activity-start limits.

## Accessibility automation

`AssistAccessibilityService` exposes:
- `screenText`, `clickText`, `typeText`, `scroll` and gestures;
- `toggleQuickSetting` (opens Quick Settings, finds the tile, reads its state, clicks only if needed);
- `toggleInSettings` (opens a settings page or panel and flips the switch next to a label);
- `autoClick` (e.g. WhatsApp's Send button);
- `dumpTree` for debugging.

`SpotifyAuto` builds on these to pick search results and press Play. The service also reads entity IDs from the Home Assistant app's widget screen.

## Popup

`PopupPresenter` prefers a `TYPE_ACCESSIBILITY_OVERLAY` window, which needs no permission. Otherwise it uses `TYPE_APPLICATION_OVERLAY`, or the translucent `AssistActivity` on the lock screen.

The overlay window is non-focusable until you tap the text box, so it never steals input from the app underneath. A custom root view handles the back key and outside touches. A small `LifecycleOwner` lets Compose run in a window with no Activity.

## Threads

| Work | Where |
|---|---|
| Wake engine, Vosk command recognition | Their own threads |
| LLM calls | One single-thread dispatcher (native engines aren't re-entrant) |
| Network (HA, weather, remote LLM) | `Dispatchers.IO` |
| Everything else (UI, controller, tools) | Main thread, through coroutines |
