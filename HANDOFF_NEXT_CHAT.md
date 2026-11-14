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

## Новый проект
Папка `Aerial-Voice-Bundle/` содержит:
- `aerial-voice/` — отдельный Java Android TV проект;
- `patch/patch_aerial_voice.py` — идемпотентный патчер HomeGuardService v1.95;
- `patch/README_RU.md` — как применять и ограничения;
- `tools/install-aerial-voice.ps1`;
- `tools/diagnose-aerial-voice.ps1`;
- `tools/remove-aerial-voice.ps1`;
- `.github/workflows/build-aerial-voice.yml`;
- `README_RU.md`.

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
