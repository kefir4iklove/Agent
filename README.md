# Agent — карманный AI-агент-кодер (Android)

Агент в духе opencode / claude code, но в виде Android-приложения: чат с LLM,
автономный цикл работы с инструментами (файлы + shell) в личном воркспейсе на телефоне.

## Возможности
- Чат с агентом, стриминг ответа в реальном времени
- Автономный агентный цикл: модель сама вызывает инструменты до выполнения задачи (до 20 шагов)
- Инструменты: `list_files`, `read_file`, `write_file`, `delete_file`, `run_shell` (в воркспейсе приложения)
- Три провайдера на выбор:
  - **Google Gemini** — по API-ключу (https://aistudio.google.com/apikey)
  - **OpenCode** — base URL + API key (OpenAI-совместимый, по умолчанию `https://opencode.ai/zen/v1`)
  - **Любой OpenAI-совместимый API** — свой base URL, ключ и модель
- API-ключи хранятся в `EncryptedSharedPreferences` (шифрование ключом устройства)

## Установка
1. Скачай APK из [Releases](../../releases) (или из артефактов сборки GitHub Actions)
2. Разреши установку из неизвестных источников и установи
3. Открой приложение → **Настройки** → выбери провайдера → вставь ключ → Сохранить
4. Пиши задачу. Файлы агента лежат в `Android/data/com.kefir.agent/files/workspace`

## Сборка из исходников
```bash
./gradlew assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`.
GitHub Actions собирает APK автоматически при каждом пуше в `main`.

Для подписи своим ключом положи в корень `keystore.properties`:
```
storeFile=agent.keystore
storePassword=...
keyAlias=agent
keyPassword=...
```
Без него release собирается с debug-подписью (устанавливается на устройство).
