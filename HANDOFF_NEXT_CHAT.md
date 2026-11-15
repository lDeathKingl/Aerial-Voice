# Aerial Voice — handoff для нового чата

## Цель
Создать отдельного русскоязычного голосового помощника для Xiaomi MiTV-MFTR0 / Android TV 14. Aerial Korobovo остаётся стабильным лаунчером; основная логика помощника должна быть отдельным приложением.

## Принятая архитектура
- Отдельный Android TV APK `app.orchard.tv.aerialvoice`.
- UI, распознавание, TTS, локальные команды и ИИ живут в отдельном приложении.
- Не использовать отдельную Accessibility-службу для глобального key filtering: на Xiaomi это может конфликтовать с HomeGuard и обходить его защиту от зависаний при подключённой мыши/геймпаде.
- Для кнопки F5 подготовлен минимальный bridge-патч в существующий Aerial 1.95 `HomeGuardService`. Он перехватывает только F5 конкретного Xiaomi remote, не меняя правила `updateKeyFilter()`.
- Пока bridge-патч не установлен, Aerial Voice запускается вручную из списка приложений, и микрофон проверяется экранной кнопкой.

## ТВ и известные данные
- Xiaomi MiTV-MFTR0, SoC MT9653, Android 14 / SDK 34, build `OS3.0.106.0.UFTMATV`.
- ADB IP на момент разработки: `192.168.2.89:5555` (может измениться).
- Bluetooth remote vendor `0x2717`, product `0x32B8`; Keyboard device `/dev/input/event10`, Consumer Control `/dev/input/event11`.
- Кнопка микрофона выдаёт `KEY_F5 DOWN` / `KEY_F5 UP` на event10. Короткое и длинное нажатия различаются длительностью, не кодом.
- event11 для микрофона не выдаёт событий.
- В `getevent -lt` видна подключённая Razer Basilisk X HyperSpeed мышь.
- Google RecognitionService настроен: `com.google.android.katniss/com.google.android.apps.gsa.binaries.tv.serviceapi.KatnissRecognitionService`.
- `com.google.android.katniss` установлен; VoiceInteractionService `com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService`.
- `assistant` и `voice_interaction_service` вручную назначались Google Katniss; китайское меню Xiaomi всё равно открывалось.
- Xiaomi voice package `com.xiaomi.voicecontrol`; короткое F5 открывает `com.xiaomi.voicecontrol/.newguide.NewGuideActivity` через `com.xiaomi.mitv.voice.SHORTPRESS`. Удержание запускает Xiaomi voice recognition.
- `com.sohu.inputmethod.sogou.tv` — китайская клавиатура; попытка запуска в логах завершалась ошибкой.
- Aerial Korobovo package `app.orchard.tv.korobovo`; Accessibility component `app.orchard.tv.korobovo/app.orchard.tv.guard.HomeGuardService`.
- `dumpsys accessibility` показывал HomeGuardService enabled/bound с capability `8` (can filter key events) и Xiaomi VoiceAccessibilityService enabled. Это capability, но не доказательство, что фильтр активен в данный момент.

## Почему не отдельный global key service
В исходниках Aerial 1.95 `HomeGuardService.updateKeyFilter()` намеренно учитывает Xiaomi, мыши и gamepad. При подключённой мыши и Xiaomi input stack `mouseHang` запрещает key filtering, так как глобальный фильтр раньше вызывал проблемы системного процесса. Поэтому Aerial Voice не должен безусловно запрашивать глобальный фильтр: он обошёл бы эту защиту и мог бы повесить ТВ.

## Присланные исходники
`Aerial-Korobovo-main.zip` содержит только:
- `.github/workflows/build-korobovo.yml`;
- `patch/Weather.kt`.
Это не полный Aerial source. Workflow клонирует `pengchujin/aerial-tv` tag `v1.95`, копирует Weather patch и собирает guard APK с `applicationIdSuffix = ".korobovo"`.

Другие архивы:
- `aerial-home-bridge-source.zip` — старое Android HOME bridge приложение `com.ts.launcher`, не голосовой помощник.
- `aerial-korobovo-qr-fix-v6.zip`, `aerial-korobovo-wttr-patch-v4.zip` — старые патчи QR/погоды.

## Структура проекта в репозитории

Репозиторий `lDeathKingl/Aerial-Voice` организован следующим образом:
- `aerial-voice/` — корень Android Gradle-проекта;
- `aerial-voice/app/src/main/java/app/orchard/tv/aerialvoice/` — Java-классы приложения;
- `aerial-voice/app/src/main/res/` — drawable и values ресурсы;
- `patch/patch_aerial_voice.py` — идемпотентный патчер HomeGuardService v1.95;
- `patch/README_RU.md` — как применять патч и его ограничения;
- `tools/` — PowerShell-скрипты установки, диагностики и удаления;
- `.github/workflows/build-aerial-voice.yml` — автоматическая сборка APK;
- `README.md` — инструкция пользователя;
- `HANDOFF_NEXT_CHAT.md` — переносимый контекст проекта.

В Aerial Voice уже реализовано:
- интерфейс на русском;
- Android SpeechRecognizer с `ru-RU`;
- TextToSpeech с `ru-RU`;
- локальные команды запуска: Zona, TorrServe, Lampa, IPTV Pro, Kinopoisk, Happ, LeanKeyboard, ADB AppControl TV, SmartTV Client for Twitch, TV Bro, VK TV, RuStore, Aerial, VLC;
- громкость, mute/unmute, pause/play, Home;
- OpenAI-compatible Chat Completions client с контекстом 12 сообщений;
- API-ключ шифруется Android Keystore AES-GCM;
- диагностика audio input devices / RecognitionService.

## Сборка
В среде создания не было Android SDK и Gradle, поэтому APK локально не собран. GitHub Actions workflow настроен на Gradle 8.9, JDK17, Android SDK34. Нужно проверить реальную сборку через Actions.

## Следующие шаги
1. Проверить проект и собрать `aerial-voice-debug.apk` через GitHub Actions.
2. Установить отдельное приложение через `install-aerial-voice.ps1`.
3. Проверить вручную запуск SpeechRecognizer с кнопки в приложении и произнести фразу по-русски через пульт. Не считать наличие RecognitionService доказательством доступа к Bluetooth-микрофону.
4. Собрать `diagnose-aerial-voice.ps1` output: RecognitionService, аудиовходы, logcat. Проверить, доступен ли remote mic приложению.
5. Только после успешного теста микрофона перейти к F5 bridge.
6. Применить `patch/patch_aerial_voice.py` к точному Aerial v1.95 source. Патч не меняет `updateKeyFilter()` и работает только когда HomeGuard сам получает key events. При подключённой Razer-мыши Aerial может намеренно не фильтровать клавиши.
7. Перед установкой patched Korobovo APK проверить подпись: новая GitHub Actions debug-сборка может быть подписана другим debug key и не обновиться поверх текущей. Не удалять установленный Aerial без резервной копии настроек.
8. Настроить AI endpoint/model/API key. ChatGPT Plus не включает API-кредиты.

## Предпочтения пользователя
- Предпочитает получать крупные законченные блоки работы, а не десятки отдельных команд.
- Не хочет рисковать стабильностью Aerial Korobovo.
- Голосовой помощник — только русский язык, максимально полноценный диалог.
- Когда чат близится к лимиту, заранее готовить переносимый handoff/архив, чтобы новый чат мог продолжить без повторных объяснений.

## Проверка 2026-11-14: штатный голосовой поиск Xiaomi и диагностическая сборка 0.2.0

- Пользователь проверил штатный голосовой поиск Xiaomi: экран голосового поиска открывается, речь распознаётся на английском и китайском, но не на русском.
- Это подтверждает, что микрофон пульта и штатный аудиотракт Xiaomi работают для поддерживаемых штатным распознавателем языков. Это **не** доказывает, что стороннее приложение может напрямую читать тот же поток.
- Следующий этап — отдельная сборка `micTest`, package ID `app.orchard.tv.aerialvoice.mictest06`. Она не заменяет установленный `app.orchard.tv.aerialvoice`.
- В диагностическом окне доступны два теста прямого `AudioRecord`: `VOICE_RECOGNITION` и `MIC`. Оба запрашивают `RC In`, затем проверяют фактический маршрут. Если маршрут уходит на Built-In Mic или не подтверждается, запись прекращается.
- Показывается уровень сигнала в реальном времени; аудио не сохраняется и не отправляется.
- Окно также запрашивает список языков настроенного Google `RecognitionService` и отмечает, присутствует ли `ru-RU`.
- Во время окна RC In теста F5 не запускает SpeechRecognizer, чтобы не конфликтовать с прямым тестом.
- Важно: не удалять и не обновлять основной Aerial Voice на этом этапе. Новая сборка устанавливается как отдельное приложение.
- Следующий шаг после установки: проверить оба источника, удерживая кнопку микрофона на пульте и говоря 5–10 секунд; затем прислать экранные результаты и вывод `diagnose-mic-test.ps1`.


## 2026-11-14: подтверждён микрофон RC In и выбран Vosk
- Пользователь установил диагностическую сборку 0.2.0 и прислал два результата.
- Android AudioManager показывает `MIBT.VoiceControl` как вход id=18, type=3; фактический маршрут AudioRecord подтверждён как `RC In` / id=18.
- `VOICE_RECOGNITION` получил хороший сигнал: -29.0 dBFS, peak 6514/32768. Обычный `MIC` получил слабый сигнал: -65.1 dBFS, peak 58/32768.
- Использовать только `MediaRecorder.AudioSource.VOICE_RECOGNITION` и выбирать вход `MIBT.VoiceControl`. Не использовать `MIC`.
- Пользователь выбрал Vosk для русского офлайн-распознавания.
- Версия 0.3.0 добавляет Vosk Android 0.3.75 + JNA 5.18.1; модель `vosk-model-small-ru-0.22` загружается один раз (~45 МБ), распаковывается в app-private files и после этого работает без сети.
- SpeechEngine использует AudioRecord 16 kHz mono PCM16, источник VOICE_RECOGNITION, выбирает RC In/MIBT.VoiceControl и подтверждает фактический route до передачи аудио в Vosk.
- Кнопка F5 переведена на push-to-talk: ACTION_DOWN запускает запись, ACTION_UP завершает фразу. Следующий запрос в режиме диалога начинается после нового нажатия.
- ИИ получает только распознанный текст; сырые аудиоданные не сохраняются и не отправляются.
- Тестовая сборка сохраняет package id `app.orchard.tv.aerialvoice.mictest06`, versionCode 3 / versionName 0.3.0, чтобы обновиться поверх 0.2.0.
- Следующий шаг: дождаться GitHub Actions build, скачать `aerial-voice-vosk-test-bundle.zip`, обновить установленное приложение, дождаться первичной загрузки модели, удерживать F5, произнести по-русски фразу и отпустить кнопку. Проверить распознанный текст.
- При проблеме прислать статус модели и вывод `diagnose-mic-test.ps1`.


## Обновление 0.4.0 (после успешного теста Vosk на телевизоре)
- Русская модель `vosk-model-small-ru-0.22` скачана и загружена; экран показал «Русская модель Vosk: готова», речь с RC In распознаётся, но точность small-модели требует дальнейшей проверки.
- Vosk работает локально; для ответов свободного диалога нужен внешний OpenAI-compatible API и отдельный API-ключ.
- В 0.4.0 добавлены проверка соединения API, зашифрованное хранение ключа через Android Keystore, локальная история последних 12 сообщений, очистка истории, настройки скорости/высоты TTS, команды «завершить диалог», «очистить диалог», «повтори», установка громкости в процентах.
- Режим диалога пока остаётся push-to-talk: удержание F5 во время каждой реплики, отпускание отправляет фразу. Не включать постоянное прослушивание без отдельной проверки, что Xiaomi RC In передаёт микрофон после отпускания кнопки.
- Основной Aerial Korobovo не менялся. F5 bridge-патч остаётся отдельным следующим этапом после тестирования текущего APK.


## Обновление 0.5.0 — переход на GigaChat
- Пользователь выбрал GigaChat API; Authorization Key уже был передан в разговоре, но его нельзя включать в исходники, APK, GitHub, логи или ZIP. На телевизоре пользователь вводит его в «Настройки ИИ» один раз; SecretStore шифрует его через Android Keystore (AES-GCM).
- Реализован GigaChat OAuth для физлиц: POST https://ngw.devices.sberbank.ru:9443/api/v2/oauth, заголовки Authorization: Basic <Authorization Key>, RqUID: UUID, тело scope=GIGACHAT_API_PERS. Access Token действует 30 минут, хранится только в памяти процесса, обновляется за 90 секунд до истечения; при 401 выполняется повторная авторизация и один повтор запроса.
- Chat endpoint: https://api.giga.chat/v1/chat/completions; модель по умолчанию GigaChat-2 (Lite), изменяется в настройках.
- Для TLS добавлен Android Network Security Config, доверяющий системным CA и официальному корневому сертификату НУЦ Минцифры. GitHub Actions скачивает https://gu-st.ru/content/lending/russian_trusted_root_ca_pem.crt в app/src/main/res/raw/russian_trusted_root_ca_pem.crt перед сборкой. Не отключать проверку TLS.
- В настройках ИИ убраны OpenAI endpoint/model поля, добавлены модель GigaChat, Authorization Key, кнопка проверки токена и ответа.
- Текущая реализация не подтверждает, что у конкретного проекта включён бесплатный Freemium: HTTP 403 будет показан как возможное отсутствие доступа к модели/лимита. Документация Сбера от 31.08.2026 говорит, что с 01.09.2026 новые клиенты или те, кто не покупал пакеты, могут оплачивать модели через cloud.ru; отдельная тарифная страница одновременно описывает Freemium для действующих клиентов. Уточнить доступ по фактическому ответу API и кабинету.
- Основной Aerial Korobovo не менялся. Следующий шаг после сборки: установить APK на TV, ввести Authorization Key непосредственно в приложении, нажать «Проверить GigaChat», зафиксировать точный текст ошибки/успеха.


## 0.5.1 — one-time ADB key bootstrap
- User cannot type the Authorization Key with the Android TV remote and asked to have it preconfigured.
- Do NOT bake the credential into the APK or commit it. A static APK secret is extractable.
- Added `MainActivity.importBootstrapKey()`: on startup reads `getExternalFilesDir(null)/aerial_voice_bootstrap_key.txt`, imports via `SecretStore.save()` (AES-GCM / Android Keystore), then overwrites best-effort and deletes the temporary file. Import result is shown on the app status line.
- Updated `tools/install-mic-test.ps1` with optional `-BootstrapKeyFile`: pushes a local one-time key file to `/sdcard/Android/data/app.orchard.tv.aerialvoice.mictest06/files/aerial_voice_bootstrap_key.txt`, launches app, polls until file disappears. The normal APK/release ZIP contains no key.
- For user convenience, prepare a one-time local PowerShell installer script containing the supplied Authorization Key, which creates a temporary local key file, calls the installer with `-BootstrapKeyFile`, removes the local temp file, and self-deletes after successful import. Do not commit that wrapper or key to GitHub.


## 0.5.2 — diagnose GigaChat no-reply and TTS no-voice
- User confirmed XiaoAI overlay is gone, RC In mic still works, and Vosk recognizes Russian. GigaChat did not answer even before XiaoAI was disabled, so do not attribute the AI failure to disabling XiaoAI or re-enable it.
- Source review found MainActivity.onFinal should call AiClient.ask for non-local commands, and AiClient reports errors to the UI. SpeechEngine.speak previously returned silently when TTS was not ready; prior TV screenshot diagnostics showed "Русская озвучка: ожидание". This is a concrete separate TTS issue.
- 0.5.2 adds a home-screen "Тест ИИ" button that calls AiClient.ask using the saved SecretStore key and the same chat endpoint path as voice requests, with visible progress and error details.
- AiClient logs request stages and HTTP status only; never log Authorization Key or access token.
- SpeechEngine now exposes TTS default engine, installed engine list, ru-RU setLanguage result, initialization state; speak() surfaces not-ready reason rather than silently returning; added TTS playback error logs and voice settings test button.
- Diagnostics dialog includes TTS detail. VersionCode 8, versionName 0.5.2. At the time of this historical note, an in-place update was expected; later signing audit found the previous workflow's debug-keystore cache was empty. Do not rely on signature continuity from 0.5.2; the 0.6.0 package is installed side-by-side instead.
- Next: build and install 0.5.2; click "Тест ИИ" and "Проверить русскую озвучку". If AI test succeeds but voice dialogue doesn't, inspect onFinal/capture path; if AI test fails, exact error will show in answer panel. If AI succeeds but TTS test fails, use diagnostics engine list to select/install a Russian TTS engine.
- XiaoAI remains disabled at user level; no system package changes in this update.

## 0.6.0 — full assistant patch

- Target device remains Xiaomi MiTV-MFTR0 / MT9653 / Android 14 SDK34; remote mic is RC In / MIBT.VoiceControl (input id 18); Vosk Russian small model and GigaChat work; RHVoice has been installed and Russian TTS works.
- App package used for current update: `app.orchard.tv.aerialvoice.mictest06`. Main package variant is `app.orchard.tv.aerialvoice`. Version 0.6.0, versionCode 9. Do not uninstall either package to solve signing errors.
- Conversation history cap increased to 30; stored GigaChat key remains encrypted in SecretStore/Android Keystore and is not embedded in APK/source.
- MemoryStore stores only explicit user-saved facts, encrypted using a separate Android Keystore AES-GCM alias. Voice commands: «запомни …», «что ты помнишь», «забудь …», «забудь всё». UI has a memory manager.
- TvContextProvider adds the most recent non-system foreground app from UsageStats and MediaSessionBridge's active title/artist/playback state. UsageStats is granted by the combined installer with appops. Notification Listener access is attempted by ADB; if unavailable, use the «Контекст ТВ» dialog to open Android notification-listener settings.
- Continuous mode starts a hands-free Vosk capture after TTS. It uses RC In / VOICE_RECOGNITION, ends an utterance after speech followed by silence, and times out after 12 seconds if no speech is detected. If Xiaomi only streams mic audio while F5 is held, use push-to-talk; this is a firmware limitation, not a Vosk language issue.
- TvInputController enumerates Android TV passthrough inputs and opens them in a full-screen TvView. This depends on the TV firmware exposing those inputs. Voice search uses ACTION_SEARCH for Lampa/Zona/Kinopoisk when supported, otherwise opens the app and explains the fallback.
- BootReceiver is optional and off by default. User enables «Автозапуск» in the UI; the installer grants SYSTEM_ALERT_WINDOW with appops to allow the BOOT_COMPLETED activity launch.
- F5 bridge v2 is applied to clean upstream Aerial v1.95 in CI. It forwards only KEY_F5 from remote vendor 0x2717/product 0x32B8: DOWN starts capture, UP stops/submits, >=650 ms marks continuous mode. It does not alter HomeGuardService.updateKeyFilter() or mouse/gamepad protection. If a mouse/gamepad is attached and Aerial disables filtering, F5 will not be intercepted; do not force-enable filtering.
- Combined workflow builds:
  - `aerial-voice-full-debug.apk` (package app.orchard.tv.aerialvoice)
  - `aerial-voice-mictest06-debug.apk` (package app.orchard.tv.aerialvoice.mictest06; current user's app)
  - `aerial-korobovo-voice-bridge.apk` (package app.orchard.tv.korobovo)
  - `aerial-voice-0.6.0-full-assistant.zip` with installer and docs.
- The combined installer uses `adb install -r` only. On `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, it does not uninstall or clear data. Voice APK may update successfully while the Korobovo bridge update is skipped due to a different signing key; if that happens, keep the old launcher and diagnose the signature before any further action.


## Подпись 0.6.0 и безопасная миграция

- Старый APK 0.5.x был собран в GitHub Actions до появления постоянного debug.keystore cache; его подпись нельзя считать совпадающей с новой. Поэтому 0.6.0 micTest использует новый package suffix `.mictest06`, устанавливается рядом со старым `.mictest` и не удаляет его.
- `install-full-assistant.ps1` сначала пытается извлечь `conversation_history_json` из `shared_prefs/aerial_voice_settings.xml` старого debug-пакета через `adb run-as`, затем импортирует последние 30 сообщений в новый пакет через `aerial_voice_bootstrap_history.json`. `MainActivity.importBootstrapHistory()` сохраняет историю и удаляет временный файл.
- Старый Authorization Key не может быть автоматически расшифрован новым пакетом: Android Keystore ключ `aerial_voice_api_key_v1` привязан к UID старого package ID. Старый `.mictest` остаётся установленным с ключом; новый installer предлагает безопасный скрытый ввод Authorization Key один раз (или принимает `-BootstrapKeyFile`). Не обещать автоматический перенос ключа.
- Workflow создаёт `~/.android/debug.keystore` детерминированным шагом после cache restore и сохраняет его через отдельный cache key v2 даже если поздняя сборка Korobovo завершится ошибкой. Последующие обновления нового package ID `.mictest06` должны использовать тот же ключ; не удалять cache v2.


## 0.6.1 stability

New branch `feature/stabilization-0.6.1` with `versionCode=10`. This is intended as an in-place update to the 0.6.0 `.mictest06` package using the same cached GitHub Actions signing key; leave old `.mictest` and Korobovo launcher untouched.
GigaChat token cache is keyed by SHA-256 fingerprint and handles expires_at seconds/milliseconds. New JUnit tests run in CI. The F5 bridge sends an explicit continuous-mode change only on a long press, and MainActivity no longer delays starting capture 320 ms. These changes require validation with the Xiaomi remote when accessible.


## Signing continuity verified (15 Nov 2026)
- Aerial Voice 0.6.0 mictest06 APK cert SHA-256: 0d5923967b313cdf47c740bd00868cdf532d0113a26e28d02b397f350ee7fe78.
- 0.6.1 build on full-assistant-0.6.0 branch (run 43699412690) despite v2 cache hit has cert SHA-256: 44ea8a3137e37814414eb908eb3a508d8024052da85329b87375241923ed58df.
- Therefore do **not** claim 0.6.0 to 0.6.1 in-place signing compatibility. User has not yet installed 0.6.0; install 0.6.1 direct as new mictest06, leave old .mictest app/data intact. If 0.6.0 exists under .mictest06, don't uninstall it automatically.
- CI now fails closed when signing key missing and checks APK cert SHA-256 against 0.6.1 baseline before publishing. A durable signing secret outside cache is advisable for future releases, but avoid exposing any private key.
