# Aerial Voice — самостоятельный голосовой помощник

**Цель:** отдельное русскоязычное Android TV-приложение для Xiaomi MiTV-MFTR0 / Android 14. Голосовой движок и интерфейс живут отдельно от Aerial Korobovo. Лаунчер не нужно переписывать ради каждой функции помощника.

## Что уже реализовано в проекте

- Отдельный Android TV APK: `app.orchard.tv.aerialvoice`.
- Русский интерфейс, крупные элементы управления и навигация пультом.
- Offline Russian speech recognition through the local Vosk model and Xiaomi RC In audio input.
- Русская озвучка через Android `TextToSpeech`.
- Локальные голосовые команды:
  - запуск Zona, TorrServe, Lampa, IPTV Pro, Кинопоиска, Happ, LeanKeyboard, ADB AppControl TV, SmartTV Client for Twitch, TV Bro, VK TV, RuStore, Aerial и VLC;
  - громче / тише / выключить / включить звук;
  - пауза / продолжить воспроизведение;
  - Домой.
- Опциональный ИИ-диалог через совместимый с OpenAI Chat Completions API endpoint.
- Контекст последних 12 сообщений.
- API-ключ не зашит в APK: он шифруется AES-GCM и хранится через Android Keystore.
- Диагностика Vosk и доступных аудиовходов.
- Отдельный безопасный PowerShell-установщик и скрипт удаления.

## Важное решение по кнопке микрофона

Я специально **не добавил в отдельное приложение постоянно работающую Accessibility-службу для глобального перехвата клавиш**.

Причина: исходный Aerial 1.95 уже содержит сложную защиту `HomeGuardService` для Xiaomi. Он намеренно ограничивает глобальную фильтрацию клавиш: на Xiaomi при подключённой мыши/геймпаде такой фильтр в прошлом вызывал проблемы вплоть до зависания системного процесса. В логах телевизора у нас видна подключённая Razer Basilisk X HyperSpeed.

Поэтому отдельная служба, которая постоянно просит Android фильтровать весь поток клавиш, была бы плохим и потенциально опасным решением.

Вместо этого подготовлен **минимальный bridge-патч для существующего HomeGuardService Aerial 1.95**:

- он перехватывает только `KEY_F5` от Xiaomi remote vendor `0x2717`, product `0x32B8`;
- короткое нажатие открывает Aerial Voice и начинает один запрос;
- удержание более 650 мс включает непрерывный диалог;
- не меняет правила `updateKeyFilter()` и все существующие защиты Aerial;
- не отключает Xiaomi VoiceControl.

Патч находится в `patch/patch_aerial_voice.py`. Он будет работать только тогда, когда штатный Aerial HomeGuard уже получает события клавиш по своим существующим правилам. На Xiaomi Android 14 это может означать, что для первого теста придётся временно отключить Razer-мышь. Принудительно включать глобальную фильтрацию при подключённой мыши нельзя.

**Пока патч не применён, Aerial Voice можно запускать из списка приложений. Удерживай кнопку микрофона: запись стартует на KEY_F5 DOWN и завершается на KEY_F5 UP.** Это позволяет проверить аудиовход и распознавание без риска для лаунчера.

## Что пока НЕ подтверждено

1. **Офлайн-распознавание Vosk.** Модель `vosk-model-small-ru-0.22` загружается при первом запуске (около 45 МБ), затем хранится во внутренней памяти приложения. После загрузки распознавание работает без сети.
2. **Русская озвучка.** Приложение выбирает `ru-RU`, но наличие русского голоса TTS зависит от движка, установленного на телевизоре.
3. **Свободный ИИ-диалог.** Он заработает после настройки API endpoint, модели и ключа. Подписка ChatGPT Plus сама по себе не является API-ключом и не оплачивает API-запросы.
4. **Перехват F5 через HomeGuard.** Патч подготовлен для точного upstream Aerial v1.95, но его ещё нужно собрать и проверить на телевизоре. Не устанавливай новую Korobovo-сборку поверх старой, пока не проверена подпись APK.

## Сборка Aerial Voice APK через GitHub Actions

Исходники уже разложены по каталогам Android Gradle-проекта в этом репозитории. Workflow находится в `.github/workflows/build-aerial-voice.yml`.

1. Открой репозиторий `lDeathKingl/Aerial-Voice` на GitHub.
2. Перейди во вкладку **Actions**.
3. Выбери **Build Aerial Voice APK** → **Run workflow**.
4. Если ветка ещё не объединена с `main`, выбери `fix/restore-android-project` в поле Branch.
5. После успешной сборки скачай artifact **aerial-voice-vosk-test-0.4.0**. Внутри будет `aerial-voice-vosk-test-bundle.zip`.

Workflow собирает APK с Gradle 8.9, JDK 17 и Android SDK 34. Для последующих обновлений workflow кеширует debug signing key в пределах этого GitHub-репозитория. Не очищай GitHub Actions cache вручную: если ключ потеряется, Android может отказаться обновлять установленное приложение поверх старого. В таком случае не удаляй приложение — сначала сравним подписи APK.

## Установка диагностической версии

Сборка 0.3.0 устанавливается **рядом** с текущим Aerial Voice под отдельным package ID `app.orchard.tv.aerialvoice.mictest06`. Она не обновляет и не удаляет установленное основное приложение.

1. Скачай artifact **aerial-voice-vosk-test-0.3.0** из GitHub Actions.
2. Распакуй `aerial-voice-vosk-test-bundle.zip` в отдельную папку.
3. В PowerShell из этой папки выполни:

```powershell
.\install-mic-test.ps1 -Tv 192.168.2.89:5555
```

Если IP телевизора изменился, замени адрес. Установщик меняет только диагностический пакет `app.orchard.tv.aerialvoice.mictest06`; текущее Aerial Voice, Aerial Korobovo, HomeGuard и Xiaomi VoiceControl не затрагиваются.

## Проверка офлайн-распознавания Vosk

1. Открой на телевизоре **Aerial Voice — RC In Test**.
2. Нажми **Проверить микрофон пульта · RC In**.
3. При первом запуске дождись окончания загрузки русской модели Vosk. На экране появится «Русское офлайн-распознавание Vosk готово».
4. Удерживай кнопку микрофона на пульте, произнеси фразу по-русски и отпусти кнопку.
5. Приложение завершит распознавание и покажет текст.

Приложение создаёт `AudioRecord` с источником `VOICE_RECOGNITION`, выбирает `RC In / MIBT.VoiceControl` и проверяет фактический маршрут через `AudioRecord.getRoutedDevice()`. Если Android не подтвердит RC In, запись останавливается — встроенный микрофон не используется.

**Сырое аудио не сохраняется и не отправляется.** Vosk обрабатывает звук локально. В ИИ-сервис отправляется только распознанный текст, если пользователь настроил API.

Для сбора результатов после теста выполни:

```powershell
.\diagnose-mic-test.ps1 -Tv 192.168.2.89:5555
```

Пришли вывод скрипта или фотографию экрана теста. По нему будет видно, загрузилась ли модель Vosk, подтвердился ли RC In и какие ошибки возникли при распознавании.

## Настройка ИИ

Распознавание Vosk работает локально и не отправляет аудио в интернет. При первом запуске приложение скачивает русскую модель (около 45 МБ) и распаковывает её во внутреннюю память. После этого само распознавание работает без сети. Small-модель рассчитана на мобильные устройства; Vosk указывает ориентир около 300 МБ оперативной памяти во время работы. Свободные вопросы и ответы ИИ по-прежнему требуют сети и настроенного API.

В приложении выбери **Настройки ИИ**:

- API endpoint — полный URL `/v1/chat/completions` или базовый URL `/v1`;
- модель — имя модели, поддерживаемой выбранным сервером;
- API-ключ — ключ провайдера.

Используй HTTPS. API-ключ шифруется Android Keystore. Текст распознанной речи и последние сообщения диалога отправляются выбранному API-провайдеру. Сырые аудиозаписи приложение не сохраняет. Распознавание выполняется локальной моделью Vosk. В ИИ-провайдера отправляется только текст распознанной фразы и контекст диалога.

## Команды первой версии

Примеры:

- «Открой Zona»
- «Запусти TorrServe»
- «Открой Lampa»
- «Открой IPTV Pro»
- «Открой Кинопоиск»
- «Открой Happ»
- «Открой LeanKeyboard»
- «Открой ADB AppControl»
- «Открой Twitch»
- «Открой TV Bro»
- «Открой VK TV»
- «Открой RuStore»
- «Сделай громче» / «Сделай тише»
- «Выключи звук» / «Включи звук»
- «Пауза» / «Продолжи»
- «Домой»

Команда «Назад» пока подсказывает использовать кнопку Back на пульте: для глобального Back потребуется отдельный безопасный мост.

## Удаление диагностической версии

Чтобы удалить только тестовое приложение:

```powershell
.\remove-mic-test.ps1 -Tv 192.168.2.89:5555
```

Основное Aerial Voice, Aerial Korobovo и системные службы останутся нетронутыми.

## Состав проекта

- `aerial-voice/` — полный исходный Android-проект.
- `patch/patch_aerial_voice.py` — минимальный F5 bridge-патч для Aerial v1.95.
- `patch/README_RU.md` — описание патча и ограничений.
- `tools/install-mic-test.ps1` — установка тестовой Vosk-версии рядом с основным приложением.
- `tools/remove-mic-test.ps1` — удаление только диагностического пакета.
- `tools/diagnose-mic-test.ps1` — сбор логов Vosk и прямого входа RC In.
- `tools/diagnose-aerial-voice.ps1` — диагностика.
- `tools/remove-aerial-voice.ps1` — удаление только Aerial Voice.
- `.github/workflows/build-aerial-voice.yml` — автоматическая сборка APK.
- `HANDOFF_NEXT_CHAT.md` — переносимый контекст для следующего чата.

## Решение конфликта подписи при установке


Если при установке появляется `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, скрипт распознает конфликт подписи и спросит разрешение удалить **только** старое тестовое приложение `app.orchard.tv.aerialvoice.mictest06`. Его локальные данные будут удалены; основное приложение Aerial Voice не затрагивается.


### Диагностика зависания загрузки
Версия 0.4.0 показывает этап подключения к серверу, HTTP-ответ, размер архива и прогресс в мегабайтах. Если соединение не устанавливается или сервер не передаёт данные, приложение выдаёт отдельную ошибку тайм-аута вместо бесконечного отображения 0%.


## Возможности 0.4.0
- Последние 12 сообщений диалога сохраняются локально; в интерфейсе есть команда очистки истории.
- В настройках ИИ можно проверить endpoint, модель и API-ключ до сохранения. Ключ по-прежнему шифруется Android Keystore.
- Добавлены настройки скорости и высоты русской озвучки.
- Голосовые команды: завершить диалог, очистить историю, повторить ответ.
- Локальная команда позволяет установить громкость в процентах.
- При начале записи текущая озвучка останавливается; Markdown-разметка и URL не читаются как набор символов.
- Системная инструкция ИИ учитывает возможные ошибки распознавания русской речи и просит уточнять неоднозначные фразы.

Важно: API-ключ OpenAI или другого совместимого провайдера пользователь вводит самостоятельно. Подписка ChatGPT Plus не является API-подпиской и не предоставляет API-кредиты.


## Aerial Voice 0.5.0 — GigaChat API
- Провайдер ИИ переключён на GigaChat API для физических лиц (GIGACHAT_API_PERS).
- Authorization Key вводится в настройках телевизора и хранится в Android Keystore через AES-GCM. Секреты не входят в исходный код, APK или ZIP.
- Приложение получает OAuth Access Token через POST https://ngw.devices.sberbank.ru:9443/api/v2/oauth, отправляя обязательный RqUID и scope=GIGACHAT_API_PERS.
- Токен кэшируется в памяти до истечения срока (30 минут по документации), обновляется заранее и повторно запрашивается при HTTP 401.
- Запросы чата идут на https://api.giga.chat/v1/chat/completions.
- Модель по умолчанию: GigaChat-2 (Lite); название можно изменить в настройках.
- Добавлен официальный корневой сертификат НУЦ Минцифры в Android Network Security Config; CI скачивает его с официального адреса перед сборкой.
- Основной Aerial Korobovo не изменяется.


## Aerial Voice 0.5.1 — one-time key import
- Added a one-time ADB bootstrap path so the user does not need to type the GigaChat Authorization Key with the TV remote.
- The installer can accept a local temporary key file using `-BootstrapKeyFile`, push it to the app-specific external files directory, and launch the app.
- On startup the app imports the file into SecretStore (AES-GCM backed by Android Keystore), wipes and deletes the temporary file, and displays an import status.
- The Authorization Key is not embedded in the APK, GitHub source, or standard release ZIP.


## Aerial Voice 0.5.2 — AI and TTS diagnostics
- Added visible progress stages for the real GigaChat conversation request.
- Added a separate "Тест ИИ" button on the home screen. It tests the saved key and the same chat request path used by recognized speech.
- Added Android TTS engine, installed engines, ru-RU language status and initialization state to diagnostics.
- SpeechEngine no longer silently discards speech when TTS is unavailable; the reason is displayed.
- Added a dedicated Russian TTS test button in voice settings.
- Added Logcat events for OAuth HTTP status, chat completion HTTP status, AI request success/failure, TTS initialization and playback. No Authorization Key or access token is logged.
- Existing XiaoAI disable state is not changed by this update.

## Aerial Voice 0.6.0 — полный голосовой помощник

Версия 0.6.0 объединяет управление кнопкой пульта, режим свободного диалога, контекст телевизора, долговременную память и автозапуск.

### Новые возможности
- F5 bridge для Xiaomi remote: нажатие начинает запись, отпускание отправляет фразу, удержание от 650 мс включает режим диалога. Bridge встраивается в существующий HomeGuardService Aerial 1.95 и не меняет updateKeyFilter().
- После ответа TTS приложение пробует автоматически открыть RC In на свободное прослушивание. Если телевизор не передаёт звук без удержания F5, приложение сообщает об этом и продолжает поддерживать push-to-talk.
- История увеличена до 30 сообщений и доступна отдельным экраном.
- Долговременная память хранится локально, зашифрована отдельным ключом Android Keystore. Пользователь управляет фактами командами «запомни…», «что ты помнишь», «забудь…» и через экран памяти.
- Контекст запроса включает последнее стороннее приложение, которое удалось определить через UsageStats, и активную медиасессию, если предоставлен Notification Listener access.
- Добавлены команды поиска фильмов через ACTION_SEARCH в Lampa/Zona/Кинопоиск, управление следующим/предыдущим треком и перемоткой, выбор HDMI/TV passthrough-входов, доступных через Android TV TIF.
- В настройках голоса можно выбрать установленный Android TTS-движок, скорость и высоту речи.
- Опциональный автозапуск после BOOT_COMPLETED. По умолчанию выключен.

### Ограничения, которые важно понимать
- Свободное прослушивание зависит от того, передаёт ли прошивка Xiaomi звук с RC In после отпускания кнопки пульта. Если нет, используйте F5 push-to-talk; приложение не включает микрофон другого устройства и не записывает звук в фоне бесконечно.
- F5 bridge работает только когда HomeGuardService уже получает события клавиш по собственной политике Aerial. Не обходить защиту мыши/геймпада и не менять updateKeyFilter().
- Текущий фильм определяется только при наличии активной MediaSession и разрешения Notification Listener. Не все видеоприложения публикуют название фильма.
- Поиск фильма передаёт запрос только тем приложениям, которые поддерживают стандартный Android ACTION_SEARCH; в остальных случаях открывается подходящее приложение.
- Список HDMI зависит от того, какие passthrough-входы прошивка раскрывает через Android TV Input Framework. Если входы скрыты, приложение откроет системные настройки источников.
- Долговременная память заполняется только явно сохранёнными пользователем фактами; сырые аудиоданные не сохраняются.

### Установка полного комплекта
Установщик сохраняет старое приложение и его данные. Если старая тестовая сборка доступна через `run-as`, он автоматически переносит историю разговоров. Authorization Key старого приложения не переносится автоматически: он зашифрован ключом Android Keystore, привязанным к UID старого пакета. Установщик безопасно попросит вставить Authorization Key один раз (можно нажать Enter и настроить позже). Старый пакет `.mictest` остаётся установленным с исходным ключом и историей. Новый ключ будет зашифрован в Android Keystore нового пакета.


## Aerial Voice 0.6.1 — stabilisation patch

- GigaChat OAuth cache is now bound to the selected Authorization Key fingerprint (the key itself is never logged or stored in the cache).
- `expires_at` is normalized from Unix seconds or milliseconds before computing when to refresh GigaChat OAuth.
- F5 voice capture starts on the next UI event-loop turn instead of waiting an additional 320 ms. Very short presses on a cold TV start still require live testing.
- Short F5 presses preserve the enabled continuous-dialog mode; holding F5 for at least 650 ms enables it.
- Added six JVM unit tests for OAuth cache behavior.
- The existing Android package IDs and user data are preserved on in-place updates from 0.6.0 to 0.6.1; old 0.5.x package is still not uninstalled.


### Важно о подписи 0.6.1
Подпись APK версии 0.6.1 **не совпала** с первоначальным архивом 0.6.0 (это проверено по SHA-256 сертификата). Нельзя устанавливать 0.6.1 поверх уже установленной 0.6.0 под тем же ID без конфликта; старую 0.6.0 не удалять автоматически. Для пользователя, который ещё не установил 0.6.0, сразу устанавливать 0.6.1 рядом со старой 0.5.x. Начиная с 0.6.1 CI требует уже существующий signing keystore и проверяет ожидаемый сертификат; несовпадение блокирует публикацию.


### Установка и самодиагностика Windows

- Для первого запуска распакуй весь ZIP и дважды кликни `install-and-check.bat`. Скрипт только на этот запуск PowerShell использует временный `-ExecutionPolicy Bypass`, без изменения постоянных политик системы. Телевизор должен быть доступен в локальной сети через ADB.
- После установки диагностику можно собрать одной командой из папки архива: `powershell -NoProfile -ExecutionPolicy Bypass -File .\\diagnose-full-assistant.ps1`. Она проверяет версии пакетов и разрешения без просмотра содержимого настройки API.
- Для журналов запуска: `powershell -NoProfile -ExecutionPolicy Bypass -File .\\diagnose-full-assistant.ps1 -WithLogs`. Проверь журнал перед пересылкой — в нём могут быть приватные голосовые фразы и имена приложений.
- Сертификат голосового APK закреплён в CI по SHA-256: `734817b1e327db7ac64cdece612746913b37faf29aa2c57e7a9c017d43b78558`. При смене ключа или пакета публикация сборки блокируется.
- При установке 0.6.1 поверх уже установленной 0.6.1 данные сохранятся, если сертификат совпадает. Если на ТВ стоит 0.6.0 под `.mictest06`, его подпись отличается, и установщик безопасно остановится без удаления.


## Aerial Voice 0.7.0 — обе модели устанавливаются ОФЛАЙН

**Главное:** никаких скачиваний Vosk или GigaAM v3 на Xiaomi TV. Один ZIP содержит Android APK, оригинальный архив Vosk Small Russian 0.22, GigaAM v3 E2E RNN-T INT8 (4 файла ONNX/tokenizer), sherpa-onnx runtime внутри APK, PowerShell-установщик и SHA-256 manifest. Все модели скачиваются и проверяются **на сервере сборки GitHub Actions** при подготовке ZIP. После скачивания готового ZIP установка и распознавание на ТВ работают без VPN, ПК и интернет-подключения. GigaChat — отдельный внешний облачный сервис и для его ответов интернет всё равно нужен.

### Порядок установки
1. Скачай полный файл `aerial-voice-0.7.0-offline-gigaam-vosk.zip` из GitHub Actions, распакуй целиком на компьютер Windows.
2. Компьютер должен видеть TV через ADB `192.168.2.89:5555`. Открой `install-and-check.bat` двойным щелчком; **не запускай APK отдельно**.
3. Установщик проверяет SHA-256 каждого файла, устанавливает APK, **передаёт обе модели по ADB** в `/sdcard/Android/data/app.orchard.tv.aerialvoice.mictest06/files/models`, затем запускает Aerial Voice. Загрузка моделей из интернета на ТВ отключена.
4. В Aerial Voice есть кнопка `Распознавание: GigaAM v3 / Vosk · офлайн`. По умолчанию используется GigaAM v3, Vosk показывает промежуточную расшифровку и остаётся резервным движком.
5. GigaAM v3 производит финальное распознавание после отпускания кнопки микрофона. Время ответа зависит от производительности CPU/ОЗУ телевизора, пока не измерено.
6. Если GigaAM недоступна, не смогла распознать фразу или завершилась с ошибкой, в разговор отправляется результат Vosk. Исходные настройки GigaChat и TTS не меняются.

### Технически
- RNNT модель: `csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16` (encoder.int8.onnx + decoder.onnx + joiner.onnx + tokens.txt), примерно 232 MB. 16 kHz mono PCM / 64 mel features / greedy search / modelType `nemo_transducer`. Локальный inference через `sherpa-onnx-2.0.1.aar` и JNI для ARM Android. CPU threads=2.
- Vosk model: `vosk-model-small-ru-0.22.zip` (архив ~45 MB), распаковка на компьютере, потом передача ADB.
- Крупные модели намеренно **не зашиты в APK**, а находятся в том же офлайн-архиве с автоматической установкой. APK содержит движки; модели устанавливаются как app-specific files. Это уменьшает риск повреждения APK и облегчает проверку больших файлов.
- GitHub Actions проверяет SHA-256 модели/движка, затем формирует `models/SHA256SUMS.txt`. PowerShell подтверждает контрольные суммы перед передачей на ТВ.
- Никаких ключей GigaChat внутри ZIP/AAR/моделей нет. Установщик переносит ключ с компьютера обычным bootstrap механизмом при необходимости, старые приложения не удаляет.
- Важно: при повторной установке **не очищай данные приложения**. Модели останутся на ТВ, а ключи/история сохраняются.

### Откуда модели и лицензии
- [GigaAM v3](https://github.com/salute-developers/GigaAM) — MIT; [совместимый экспорт для sherpa-onnx](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16).
- [sherpa-onnx Android](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) — Apache 2.0.
- [Vosk Small Russian](https://alphacephei.com/vosk/models) — бесплатная локальная русская модель.
