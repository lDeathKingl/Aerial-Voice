## 0.9.1 — compact voice overlay, film metadata and HomeGuard compatibility

- Added translucent FloatingVoiceActivity for push-to-talk, continuous dialogue, GigaChat, RHVoice, commands and shared conversation history.
- Routed optional Accessibility F5 and system assistant sessions to the small voice UI instead of full-screen MainActivity; old HomeGuard MainActivity intents are forwarded without changing Aerial Korobovo.
- Safe Windows setup now enables only the optional F5 bridge by default, with an original-settings backup and restore path; system assistant replacement is a separate advanced action.
- Fixed Zona discovery for multiple package owners of the same search provider authority.
- Added real provider-supplied film year/rating/genre, a short-lived voice-selectable results list and follow-up "open second" commands.
- Added best-effort last opened film context without claiming playback, and TTS acceptance checks so unsupported audio cannot hang the floating dialogue.
- Verified on CI only; actual Xiaomi RC In, HomeGuard, app overlay focus, Zona 3.0.79 and HDMI/CEC remain device-specific.

## 0.9.0 — unified cinema search, optional global voice and reliable audio controls

- Added real Android ContentProvider search for exported Zona and other cinema sources, plus dynamic SearchManager discovery.
- Added TV search UI with results, optional poster preview, source labels and clickable Android result links.
- Added guarded VoiceInteractionService, RecognitionService and opt-in Accessibility F5 key bridge, with Windows ADB setup/restore scripts.
- Added read-back volume verification: no fake success when firmware ignores audio commands.
- Added adaptive speech endpointing (0.9–2.5 sec silence), user configuration and JUnit regression tests.
- Kept GigaAM v3, GigaChat, RHVoice, conversations, memory and installer signing cache.
- CI success proves buildability, not the functionality of external Zona 3.0.79/Chinese Xiaomi firmware.

## 0.8.1 — GigaAM-only / Zona 3.0.79

- Removed Vosk Android dependency, model manager, selector and installer payload. Migrates old ASR choices to GigaAM.
- Added packaged offline GigaAM model verification and Windows no-uninstall installer.
- Added voice film search commands prioritizing Zona, with package/version diagnostics and explicit fallback on unsupported ACTION_SEARCH.
- Preserved GigaChat, conversation history, memory, TTS, F5 mic input and TV commands from the 0.6.4 branch.
- Added CI compilation, unit tests and model checksum checks; real TV routing and Zona search require hardware verification.

## 0.6.1 — Stabilization

- Fixed GigaChat token cache ownership by Authorization Key and supported both seconds/milliseconds OAuth expiry values.
- Removed 320 ms F5 start scheduling delay and preserved continuous-dialog mode on short presses.
- Added JUnit regression tests to GitHub Actions build.
- Kept package IDs and existing app data unchanged on update.

# Changelog

## 0.5.2 — GigaChat and TTS diagnostics
- Added a home-screen test that exercises the saved-key GigaChat request path.
- Added visible AI request stages and detailed API error display.
- Added TTS engine/language diagnostics and a voice test button.
- Prevented silent TTS failures; logs omit all credentials and tokens.
- No Xiaomi system packages are changed.


## 0.5.1 — one-time GigaChat key import
- Added ADB one-time key bootstrap; no TV keyboard is needed.
- App imports the temporary file into Android Keystore-backed SecretStore and deletes the temporary file.
- The key is not included in the APK, repository, or standard build artifact.


## 0.5.0 — GigaChat API
- Добавлен отдельный клиент GigaChat REST API с OAuth-авторизацией для физлиц.
- Authorization Key хранится только на Android TV в Android Keystore-backed AES-GCM SecretStore.
- Добавлены кэширование/обновление Access Token и повтор запроса при HTTP 401.
- Добавлена проверка OAuth и генерации ответа прямо из настроек.
- Используется актуальный API endpoint api.giga.chat и модель по умолчанию GigaChat-2.
- Добавлен доверенный корневой сертификат НУЦ Минцифры через Android Network Security Config.


## 0.4.0 — диалог, ИИ и голосовые настройки
- Добавлена проверка соединения с OpenAI-compatible API до сохранения настроек.
- Улучшена обработка ошибок API, тайм-аутов и вариантов текстового содержимого ответа.
- История последних 12 сообщений сохраняется локально и может быть очищена из интерфейса.
- Добавлены настройки скорости и высоты русской озвучки.
- Добавлены голосовые команды завершения диалога, очистки истории и повтора ответа.
- Добавлена установка громкости по проценту.
- TTS не читает Markdown-разметку и URL; при начале записи текущая озвучка останавливается.
- Обновлена системная инструкция ИИ для коррекции очевидных ошибок распознавания русской речи.


## 0.3.1 — диагностика загрузки модели Vosk
- Исправлена индикация загрузки: приложение показывает этап подключения, ответ HTTP, размер архива и скачанные мегабайты.
- Прогресс обновляется по мере загрузки, а не только на отметках каждых 5%.
- Добавлено явное сообщение при тайм-ауте соединения с сервером модели.
- Увеличена версия приложения до 0.3.1.


## 0.3.0 — офлайн-распознавание Vosk и прямой микрофон Xiaomi
- Добавлены Vosk Android 0.3.75 и JNA 5.18.1.
- Добавлена загрузка и безопасная распаковка русской модели `vosk-model-small-ru-0.22` при первом запуске; после установки распознавание работает офлайн.
- Удалена зависимость распознавания от Android Google RecognitionService.
- Запись выполняется через AudioRecord / VOICE_RECOGNITION с выбором и проверкой RC In / MIBT.VoiceControl.
- Кнопка F5 работает как push-to-talk: запись начинается на нажатии и финализируется при отпускании.
- ИИ получает только распознанный текст; аудиопоток не сохраняется и не отправляется.

## 0.2.0 — прямая диагностика микрофона пульта
- Добавлен отдельный диагностический flavor `micTest` с package ID `app.orchard.tv.aerialvoice.mictest`, чтобы не заменять уже установленный Aerial Voice.
- Добавлен прямой тест входа Xiaomi `RC In` через Android `AudioRecord` с запросом предпочтительного аудиоустройства.
- Проверяется фактический маршрут записи; при переключении на Built-In Mic тест останавливается.
- Отображается уровень входного сигнала; сырое аудио не сохраняется и не отправляется.
- Запрашивается список языков текущего Android RecognitionService, включая наличие `ru-RU`.

## 0.1.0 — первый самостоятельный прототип
- Отдельное Android TV-приложение Aerial Voice.
- Русский интерфейс, Android SpeechRecognizer и TextToSpeech.
- Локальные команды запуска приложений, громкости и воспроизведения.
- OpenAI-compatible Chat Completions client с зашифрованным API-ключом.
- Диагностика аудиовходов и recognition service.
- PowerShell install/diagnostics/remove scripts.
- Отдельный минимальный патчер F5 bridge для Aerial 1.95; не использует самостоятельную глобальную Accessibility-службу.

## 0.6.0 — Full Assistant Patch

- Added Xiaomi F5 DOWN/UP bridge through Aerial 1.95 HomeGuardService; preserved all existing key-filter, mouse, and gamepad safety checks.
- Added hands-free Vosk capture after TTS with voice-activity endpointing and a bounded no-speech timeout; PTT remains the fallback when Xiaomi RC In is only active while the remote button is held.
- Increased conversation history to 30 messages and added a history viewer.
- Added locally encrypted long-term memory with explicit save/forget commands and a management screen.
- Added TV context snapshots using UsageStats and optional active MediaSession metadata.
- Added TTS engine selection, HDMI/TV input picker, ACTION_SEARCH movie handoff, media next/previous/rewind/fast-forward commands, and optional boot autostart.
- Added a combined CI bundle containing Aerial Voice main/micTest APKs and a Korobovo v1.95 bridge APK.
- No API credentials are embedded in source or APK. Existing GigaChat Authorization Key remains in Android Keystore.

- Added one-time history migration from the old debug package via adb run-as; old package data is not removed.
- Corrected migration expectations: the old GigaChat key is Android Keystore UID-bound and cannot be exported by the new package. Installer prompts for one-time re-entry or accepts a local bootstrap file; old app remains installed with its key.
- Added a concrete Russian TTS voice picker, not only engine selection.
- Made the new debug signing key cache persistent even if the later Korobovo build fails.
