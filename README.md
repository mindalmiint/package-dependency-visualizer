# Визуализатор зависимостей пакетов

Вариант №8. Язык программирования Java.

## Этап 1

Чтение и проверка настроек из JSON, вывод в формате `ключ=значение`.

## Этап 2

Добавлено получение прямых зависимостей NuGet-пакета по URL репозитория.
Выбирается последняя стабильная версия. Выводятся зависимости,
диапазоны версий и целевые платформы.

При `testMode=true` данные читаются из локального JSON.
При `testMode=false` — через NuGet V3 API.

## Файлы

- `src/Main.java` — запуск и обработка ошибок.
- `src/Config.java` — настройки.
- `src/ConfigLoader.java` — проверка настроек.
- `src/FlatJson.java` — разбор JSON, включая массивы и вложенные объекты.
- `src/NuGetClient.java` — получение и разбор зависимостей.
- `config.json` — конфигурация.
- `examples/test-repository.json` — тестовые данные.
- `tests/ConfigLoaderTest.java` — тесты первого этапа.
- `tests/DependencyTest.java` — тесты второго этапа.
- `run.sh` — сборка и запуск.

## Запуск

```bash
bash run.sh
```

## Тесты

```bash
mkdir -p out
javac -encoding UTF-8 -d out src/*.java tests/*.java
java -Djava.io.tmpdir=out -cp out ConfigLoaderTest
java -Djava.io.tmpdir=out -cp out DependencyTest
```