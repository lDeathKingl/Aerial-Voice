# Changelog

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
