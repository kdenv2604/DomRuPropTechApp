# Дом.ру — служебный доступ для iOS

Нативный порт служебного Android-клиента: **Swift / SwiftUI, iOS 15 и новее**.
Android остаётся отдельным нативным проектом в `../android/`; его прикладной код
при переносе не изменялся. Тестовая сборка подписана через Personal Team и
установлена на личный iPhone; запуск подтверждён пользователем. iOS пока не опубликована.

## Поведение

- Несколько служебных логинов и отдельная сессия каждого аккаунта.
- Логины, пароли и токены в Keychain: только это устройство, без iCloud-синхронизации.
- Обновление токена, ограниченный автовход, защита новой сессии от старых ответов.
- Общее дерево «город → улица и дом → подъезд», поиск и запоминание раскрытой ветки.
- Объединение доступов к одной двери с сохранением источников и прав на видео.
- Открытие через один выбранный доступ без автоматического повтора POST.
- Красная кнопка открытия, зелёное подтверждение, оранжевый ручной повтор.
- Получение кода с датами обновления/окончания, если сервер их прислал.
- Только встроенный предпросмотр камеры, без звонков и полноэкранного режима.
- Автовосстановление видео после ошибки, окончания, отсутствия кадров, смены сети
  и возврата из фона. Новая ссылка и новое соединение, даже если URL совпал.
- Светлая/тёмная/системная тема, правая рука по умолчанию, зеркальные кнопки для левой.
- Настройки по шестерёнке, аккаунты отдельно, версия и сборка в настройках.
- Демо без сетевых запросов к Дом.ру и без настоящего открытия дверей.

## Соответствие Android

| Android | iOS | Ответственность |
|---|---|---|
| `model/Models.kt` | `Sources/Domain/Models.swift` | Модели, состояния, ошибки |
| `DomruOidcClient` | `Data/OIDCClient` | Штатный вход, refresh, logout |
| `SecureSessionStore` | `Data/SessionVault` | Защищённое хранилище, сравнение ревизий |
| `DomruSessionProvider` | `Data/SessionProvider` | Один refresh на аккаунт, восстановление входа |
| `ProptechApiClient` | `Data/PortalAPI`, `HTTPTransport` | API и безопасная политика запросов |
| `RemoteCatalogBuilder`, `HouseRoute` | `Data/RemoteCatalogBuilder`, `Domain/AddressHierarchy` | Привязка устройств и разбор адресов |
| `AddressHierarchyMerger`, `AddressSearch` | `Domain/AddressHierarchy` | Объединение и поиск |
| `ProptechDomruRepository`, `CombinedAccessService` | `Data/ServiceRepository` | Каталоги и выбор разрешённого источника |
| `AppViewModel` | `App/AppModel` | Состояние UI, отмена устаревших результатов |
| `VideoReconnect`, `LiveCameraPlayer` | `Video/CameraController`, `LiveCameraPlayer` | Ссылка, жизненный цикл и видеоплеер |
| Compose-экраны и `AppPreferences` | `UI/`, `App/AppPreferences` | SwiftUI и локальные настройки |

Каталог загружается теми же методами портала и объединяется с сохранением порядка
аккаунтов. В iOS готовые дома и подъезды общего каталога индексируются сразу,
поэтому раскрытие веток не требует дополнительных сетевых запросов.
При временном сбое одного источника доступные объекты других источников остаются.
Неполный ответ не сохраняется в кеш как полный.

## Сборка на Mac

Рабочая копия на общем Mac: `~/Projects/DomRuPropTechApp/ios`.
Подключение с Windows: `ssh mac-mini`; параметры SSH хранятся в общей конфигурации
пользователя. Приватные ключи и Android-keystore на Mac не копируются.

Из Windows исходники можно передать и собрать одной командой из корня репозитория:

```powershell
./ios/scripts/build-on-mac.ps1
./ios/scripts/build-on-mac.ps1 -Test -SimulatorID UUID_СИМУЛЯТОРА
./ios/scripts/build-on-mac.ps1 -Target Device -Configuration Release
```

Скрипт использует общий SSH-алиас `mac-mini`, передаёт только исходники iOS и не
содержит IP, паролей или приватных ключей. Сборка для устройства остаётся без подписи.

```sh
cd ios
bash scripts/bootstrap-vlc.sh
xcodebuild -project DomruTechnics.xcodeproj -scheme DomruTechnics \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build CODE_SIGN_IDENTITY=- build
```

`DomruTechnics.xcodeproj` и shared scheme включены в Git. После добавления/удаления
Swift-файлов проект можно детерминированно обновить:

```sh
python3 scripts/generate-project.py
```

Для тестов выберите установленный симулятор из `xcrun simctl list devices available`:

```sh
xcodebuild -project DomruTechnics.xcodeproj -scheme DomruTechnics \
  -destination 'platform=iOS Simulator,id=UUID_СИМУЛЯТОРА' \
  -derivedDataPath build CODE_SIGN_IDENTITY=- test
```

Xcode 27 содержит XCTest с минимальной iOS 17: это ограничение **тестовых bundle**.
Само приложение собирается с `IPHONEOS_DEPLOYMENT_TARGET=15.0`.
Новые среды симуляторов автоматически не скачиваются.
Для симулятора используется локальная ad-hoc подпись (`CODE_SIGN_IDENTITY=-`),
не требующая Team или аккаунта разработчика. Отключать подпись у тестового хоста
нельзя: иначе iOS Simulator запрещает приложению работу с Keychain.

Проверка сборки для устройства без подписи:

```sh
xcodebuild -project DomruTechnics.xcodeproj -scheme DomruTechnics \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath build-device CODE_SIGNING_ALLOWED=NO build
```

Для установки на личный iPhone в Xcode нужно выбрать собственный Team в Signing &
Capabilities, подключить/доверить iPhone и включить Developer Mode, если система
этого требует. Неподписанная `.app` не является установочным аналогом APK.
Team, сертификаты и профили пользователя остаются локальными на Mac и не включаются
в репозиторий. Результат первой установки указан в отчёте проверки.

## Видео и зависимость VideoLAN

Служебный API выдаёт в том числе FLV, поэтому используется **MobileVLCKit 3.7.3**,
а не ограниченный набором AVPlayer контейнеров плеер. Библиотека поддерживает
HTTP-FLV/HLS/RTSP; фактические URL берутся только из ответа разрешённой двери.
Звук в предпросмотре отключён. API-токены не передаются медиаплееру.

`bootstrap-vlc.sh` скачивает неизменённый XCFramework с официального сайта VideoLAN
и проверяет закреплённый SHA-256. Архив около 252 MiB хранится в исключённой из Git
папке `Vendor/`; он не скачивается при каждом запуске приложения.

- [Архив MobileVLCKit 3.7.3](https://download.videolan.org/pub/cocoapods/prod/MobileVLCKit-3.7.3-319ed2c0-79128878.tar.xz).
- SHA-256: `0d04059906962ddc9a7bd1ebaa12e1f9ae85eb2466116a97a2f46886dd27a0a9`.
- [Исходники и инструкции сборки VLCKit](https://github.com/videolan/vlckit).
- Лицензия LGPL 2.1+ включена в Resources и доступна в настройках приложения.

Для замены библиотеки собственной совместимой сборкой положите её в
`Vendor/MobileVLCKit-binary/MobileVLCKit.xcframework` и пересоберите приложение.
Проект использует динамическую библиотеку; собственные исходники приложения
доступны для повторной сборки. Перед публикацией отдельно проверяются актуальные
требования магазина к SDK/privacy manifest и комплекту лицензионных материалов.

Демо-ролик `Resources/demo-camera.flv` получен без перекодирования H.264 из
`../android/app/src/androidTest/assets/local-camera.mp4`:

```sh
ffmpeg -i ../android/app/src/androidTest/assets/local-camera.mp4 -c:v copy -an \
  -f flv Resources/demo-camera.flv
```

FFmpeg для обычной сборки не требуется. Иконка сохраняет исходную векторную
геометрию Android; PNG разных размеров генерируются `scripts/make-icon.swift`.

## Проверка и ограничения

Результаты текущего прогона записаны в [../docs/IOS_VALIDATION.md](../docs/IOS_VALIDATION.md).
Тесты не используют реальные пароли, домофоны или коды. Работа настоящего портала
на iPhone требует отдельной ручной проверки с разрешённой служебной учёткой.
Компиляция с минимальной iOS 15 не заменяет проверку на устройстве с iOS 15.
