# Boosty ATV

[![Релиз](https://img.shields.io/github/v/release/cergo666/BoostyATV)](https://github.com/cergo666/BoostyATV/releases/latest)
[![Загрузки APK](https://img.shields.io/github/downloads/cergo666/BoostyATV/total)](https://github.com/cergo666/BoostyATV/releases)
[![Звёзды](https://img.shields.io/github/stars/cergo666/BoostyATV)](https://github.com/cergo666/BoostyATV/stargazers)
[![Сборка](https://github.com/cergo666/BoostyATV/actions/workflows/android.yml/badge.svg)](https://github.com/cergo666/BoostyATV/actions/workflows/android.yml)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android)](https://github.com/cergo666/BoostyATV/releases/latest)

**[Скачать APK](https://github.com/cergo666/BoostyATV/releases/latest/download/BoostyATV.apk)** · [История изменений](CHANGELOG.md)

Неофициальный клиент Boosty для телефона и Android TV. Один APK для Android 8.0 и новее.

## Возможности

- Лента подписок с автоматической подгрузкой и отдельным режимом «Видео». На телефоне подписки открываются кнопкой ☰ слева. Внутри канала есть поиск видео по названию и фильтр по тегам.
- Встроенный плеер с выбором качества, точным переходом по времени и управлением с пульта. Поддерживаются HLS, DASH и MP4.
- Сохранение позиции просмотра на устройстве и синхронизация с Boosty. На карточках видео виден прогресс.
- Внешние плееры VLC, MX Player, ViMu и Just+ Player. Приложение передаёт сохранённую позицию и сохраняет новую, если плеер возвращает её.
- Комментарии открываются под видео. Ответы раскрываются на месте. Реакции на публикации и комментарии можно ставить, менять и убирать.

## Установка и обновления

Скачайте APK из последнего релиза и откройте его на телефоне или Android TV. Если у вас стоит одна из тестовых сборок 0.x, удалите её перед установкой 1.0.0. Android попросит войти в Boosty заново после удаления.

В приложении откройте **⋯ → О программе → Проверить обновления**. При выходе новой версии приложение покажет список изменений и предложит скачать APK. Установку подтверждает Android.

## Скриншоты

<img src="docs/screenshots/feed-phone.png" alt="Лента на телефоне в портретной ориентации" width="280">

<img src="docs/screenshots/feed-landscape.png" alt="Лента на телефоне в альбомной ориентации" width="720">

## Вход и подключение ТВ

- Введите номер, привязанный к аккаунту Boosty. Страну можно выбрать по флагу или вставить полный номер с `+`. Код приходит по каналу, который выберет Boosty.
- На телефоне доступен вход через ВК на странице Boosty. Если VK ID покажет CAPTCHA, пройдите её там же. Google требует системный браузер. Для аккаунта Google с привязанным номером доступен вход по номеру. [Политики Google OAuth](https://developers.google.com/identity/protocols/oauth2/policies#secure-browsers)
- Чтобы передать действующий вход, подключите телефон и ТВ к одной локальной сети IPv4. На ТВ нажмите **«Войти с телефона»**. После входа на телефоне нажмите **«QR»** в ленте, отсканируйте код встроенной камерой и выберите **«Передать текущий вход»**. После истечения токена доступа отсканируйте новый QR-код или войдите на ТВ по номеру для отдельной сессии.

## Сборка

Проверено с JDK 21 и Android SDK Platform 37. Укажите путь к SDK в `ANDROID_HOME` или `local.properties`.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug --parallel
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Для установки на подключённое устройство выполните `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

В качестве референсов использованы [BoostBuddy](https://github.com/slartus/BoostBuddy), [BoostyVP](https://github.com/rico-x/BoostyVP) и [boosty-tv-app](https://github.com/ARDcode/boosty-tv-app).


## Автор

[Sergey Molchanov (cergo666)](https://github.com/cergo666)

## История звёзд

[![График звёзд](https://api.star-history.com/svg?repos=cergo666/BoostyATV&type=Date)](https://star-history.com/#cergo666/BoostyATV&Date)
