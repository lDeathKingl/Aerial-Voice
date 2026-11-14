# Aerial Voice bridge v2 для Aerial 1.95

Патч добавляет передачу только кнопки микрофона Xiaomi в отдельное приложение Aerial Voice.

## Поведение
- Обрабатывается только KEY_F5 от remote vendor `0x2717`, product `0x32B8`.
- ACTION_DOWN запускает Aerial Voice и начинает запись с RC In.
- ACTION_UP останавливает запись и отправляет распознанную фразу.
- Удержание от 650 мс включает режим диалога; после ответа Aerial Voice пробует автоматически слушать дальше.
- Пакет `app.orchard.tv.aerialvoice.mictest` проверяется первым, затем основной `app.orchard.tv.aerialvoice`.

## Безопасность
- Патч не изменяет `HomeGuardService.updateKeyFilter()`.
- Патч не меняет существующие проверки мыши, геймпада, Xiaomi и CEC.
- Никакой отдельной глобальной Accessibility-службы не добавляется.
- Если Aerial по своей политике не фильтрует клавиши (например, из-за подключённой мыши на Xiaomi), F5 bridge не получит событие. Не включай фильтрацию принудительно: эта защита предотвращает зависания system_server.

## Применение
В корне чистого upstream `pengchujin/aerial-tv` tag `v1.95`:
```bash
python3 patch/patch_aerial_voice.py <aerial-v1.95-source-root>
```
Скрипт идемпотентен для v2 и завершится с ошибкой, если обнаружит старый v1. CI дополнительно собирает Korobovo guard APK с package suffix `.korobovo`.

## Установка
Используй `tools/install-full-assistant.ps1` из полного релизного архива. Он не удаляет старый Korobovo при конфликте подписи. Если установка Korobovo отклонена Android, Aerial Voice останется обновлённым, а текущий лаунчер останется нетронутым.
