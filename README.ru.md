# proto-re

Русский | [English](README.md)

Нативный порт Prototype (2009) на Android. Не эмулятор и не Wine: `prototypeenginef.dll` статически
перекомпилирован из x86 в C, собран под arm64 и работает поверх своей прослойки Win32/D3D9 (SDL2, DXVK, FAudio).
В APK нет файлов игры, их надо скопировать из своей копии.

План: [ROADMAP.md](ROADMAP.md). Заметки по движку: [re/subsystems.md](re/subsystems.md).

## Что есть

На телефоне работают меню, ролики, звук, миссии, сохранения. Полное прохождение ещё не проверял. Русский текст работает (фанатский перевод).

- сенсорное управление, редактор раскладки, чувствительность камеры, инверсия Y, вибрация
- геймпад
- на Mali системный Vulkan, на Adreno Turnip (Mesa) через libadrenotools
- настройки в игре: разрешение рендера, текстуры, лимит FPS, экономия батареи, мёртвая зона стика
- ADPF, чтобы телефон не гонял процессор на максимуме, плюс термозащита: при нагреве лимит FPS
  ступенчато снижается (45, потом 30) и возвращается, когда телефон остынет
- экран первого запуска: проверка скопированных файлов игры, консервативный автоподбор пресета качества,
  бэкап сохранений при их изменении (последние 5 в `files/backups`)

Pixel 6 (Mali-G78): в меню 60 fps, в игре 38-46 в 720p и 33-42 в 1080p. Сейчас всё упирается в процессор.
Телефон с Adreno 735: в среднем около 40 fps в 720p.

## TODO

- 60 fps в 1080p. В основном процессор: переписать вручную горячие функции главного и рендер-потока, меньше
  блокировок буферов в D3D9, PGO (ThinLTO собирается с `LTO=1`; даёт ли прирост, нужны замеры на устройстве).
  Потом, может быть, симуляция 30 Гц с интерполяцией до 60 и апскейл FSR/ASR.
- встроенный бенчмарк, чтобы пресет качества при первом запуске выбирался по замеру, а не только по объёму
  RAM; динамическое разрешение по тепловому запасу, а не только снижение лимита FPS
- текстуры ASTC/ETC2, меньше нагрев и расход батареи
- управление: кнопки по ситуации в драке (захват/бросок, поглощение, щит), колесо сил и маскировок свайпом,
  захват ближайшего врага, отдельные раскладки для вертолёта и танка
- проверить на живых Adreno и слабых Mali (G57/G68)
- запасной рендер на GLES 3.0 для телефонов с плохим Vulkan
- выбор языка в настройках, подключение озвучки, если вдруг появится русская

## Установка

Android 10+, arm64 и купленная игра (PC, Steam app 10150, сборка 19788432).

1. Поставить `prototype.apk` и открыть его: экран первого запуска создаст папку, покажет её путь
   и будет отмечать файлы по мере копирования — или нажмите там «Choose game folder...» и укажите папку
   установленной PC-игры: нужные файлы скопируются на место сами.
2. Скопировать `prototypeenginef.dll`, все `.rcf` и `.p3d` и папки `art`, `audio`, `scripts` в
   `/sdcard/Android/data/com.protore.game/files/game/`. Для русского нужны `.rcf` с наложенным переводом.
3. По желанию положить `protore.ini` в `/sdcard/Android/data/com.protore.game/files/`
   (пример: [android/protore.ini.example](android/protore.ini.example)). Нужны права на чтение, `chmod 666`.

Все сборки подписаны одним и тем же отладочным keystore из репозитория (`android/debug.keystore`), а CI
проставляет растущий код версии, поэтому новый APK ставится поверх старого как обычное обновление —
файлы игры и сохранения не теряются. Удалить приложение вручную придётся только один раз, при переходе
со старой CI-сборки, подписанной одноразовым ключом раннера.

На Adreno в разделе «Turnip driver» экрана первого запуска можно импортировать стандартный ZIP-драйвер
Mesa Turnip для AdrenoTools (`.zip`/`.adpkg.zip`: `meta.json` и указанный в нём `.so`; архив заранее
распаковывать не надо). Драйвер хранится во внутренней памяти приложения и со следующего запуска заменяет
Turnip, встроенный в APK; кнопка «Use bundled Turnip» возвращает встроенный. Если импортированный драйвер
не загрузится, игра запишет причину в лог и возьмёт встроенный или системный драйвер. Импортируйте только
драйверы из надёжного источника — ZIP с драйвером это нативный код.

Логи там же (`proto-native.log`, `app_process64_d3d9.log`) и в `adb logcat -s protore`.

Ключи `protore.ini`:

| ключ | |
|---|---|
| `render_height` | 720, 900, 1080, 0 = экран |
| `texture_skip_mips` | 0 оригинал, 1 вдвое меньше, 2 вчетверо |
| `fps_limit` | 30 / 45 / 60 |
| `battery_saver` | 30 fps, без привязки к большим ядрам |
| `perf_hint` | ADPF вкл/выкл |
| `vulkan_driver` | `auto` (импортированный/встроенный Turnip на Adreno) / `system` / `turnip` (повтор принудительно) |
| `present_stats` | статистика DXVK в лог |
| `import_profile`, `watchdog` | профилирование, отладка зависаний |

Любой ключ можно задать переменной окружения `PROTORE_<KEY>`.

## Как устроено

`tools/recomp/recomp.py` (capstone) превращает каждую функцию движка в `void f_<va>(Ctx*)` с явным
состоянием процессора: флаги с анализом живости, x87 и SSE, таблицы переходов, setjmp/longjmp, атомики.
Все 73 595 настоящих (не-thunk) функций проходят без неподдерживаемых инструкций; thunk'и и
эвристические extra-записи из `data_entries.py` тоже генерируются и в итоговой строке считаются
отдельно (ложные срабатывания среди них — мёртвый код).

`src/native` это рантайм: загрузчик PE, нужные движку импорты Windows, D3D9 через DXVK, XAudio2 через FAudio,
ввод через SDL2. Пара горячих мест (скиннинг, звук) написана руками.

Часть функций переписана вручную на C++ (как в re3/reVC) и сверяется с оригиналом дифтестами внутри процесса
под Wine (`src/host`, прокси `binkw32.dll`). Пока около 230 функций, расхождений нет.

| путь | |
|---|---|
| `src/native/` | рантайм: загрузчик, Win32, мост D3D9, звук, ввод, ADPF, Turnip |
| `src/recomp/` | заголовки для перекомпилированного кода, мост x86 |
| `src/host/` | прокси для Wine: хуки, дифтесты, пробы |
| `src/core/`, `src/math/` | движок, восстановленный вручную |
| `src/tests/` | дифтесты |
| `android/` | Java (activity, сенсорное управление), скрипты сборки, патчи DXVK |
| `tools/recomp/` | перекомпилятор, `bisect.sh` |
| `tools/` | утилиты для RCF/P3D, генератор моста D3D9 и прочее |
| `tools/ghidra/` | скрипты для headless Ghidra |
| `re/` | заметки, проблемы, список функций |

## Сборка

Linux. Нужны Android NDK, JDK, SDK build-tools, python3 с `capstone` и `pefile`, cmake, ninja, meson >= 1.4.
Для Turnip ещё mako, pyyaml, bison, flex, glslangValidator. Для Wine: `mingw-w64-gcc` (i686), wine, dxvk.
`PROTORE_ENGINE` указывает на оригинальный DLL
(по умолчанию `~/Downloads/Prototype (2009)/Prototype/prototypeenginef.dll`).

APK:

```sh
android/build-deps.sh arm64-v8a ~/proto-re-data/android-deps-src ~/proto-re-data/android-deps/arm64-v8a
android/build-turnip.sh ~/proto-re-data/android-deps-src ~/proto-re-data/android-turnip
python3 -I tools/recomp/data_entries.py
python3 -I tools/recomp/recomp.py --functions all --base 0x58000000 --out build/recomp/native --split 400
tools/cool_make.sh -C src/native PLATFORM=android ABI=arm64-v8a
android/build-apk.sh arm64-v8a
android/verify-apk.sh build/apk/prototype.apk arm64-v8a
adb install -r build/apk/prototype.apk
```

Зависимости (SDL2, FAudio, DXVK, Turnip) ещё собирает GitHub Actions. `cool_make.sh` это make в 2 потока,
который ставит сборку на паузу, когда процессор перегревается: без него ноут один раз выключился.

GitHub Actions умеет собирать и весь APK (`.github/workflows/android-apk.yml`). На каждый push собирается
CI-APK со стаб-таблицей движка (`tools/recomp/make_stub.py`): зависимости, Turnip, рантайм, Java, упаковка,
подпись и `android/verify-apk.sh` проходят целиком, но кода игры в таком APK нет и играть в него нельзя.
Настоящий APK собирается ручным запуском того же workflow с `engine=full`, и форма запуска попросит ссылку
на ваш `prototypeenginef.dll`: загрузите его на Hugging Face (в свой репозиторий; подойдёт ссылка на страницу
файла `.../blob/main/...` — `android/download-engine.py` сам перепишет её в ссылку скачивания) или на
Google Диск (Доступ → «Все, у кого есть ссылка»), затем при запуске вставьте ссылку в поле `engine_url`;
необязательное поле `engine_sha256` проверит скачанное по контрольной сумме. Осторожно: значения полей запуска
видны в публичной истории прогонов, то есть такую ссылку сможет открыть любой — для приватной ссылки оставьте
поле пустым и вместо него задайте секрет репозитория `PROTORE_ENGINE_URL` (плюс необязательные
`PROTORE_ENGINE_SHA256` и read-Token `HF_TOKEN` для приватного репозитория Hugging Face). В обоих вариантах
DLL и сгенерированный C остаются на раннере и удаляются перед загрузкой артефакта — сохраняется только APK.

Сборка для ПК (DXVK 2.5.3 с `-Denable_d3d9=true -Ddxvk_native_wsi=sdl2` и FAudio в `~/proto-re-data/deps`):

```sh
tools/cool_make.sh -C src/native
cd ~/proto-re-data/native_run && PROTORE_WINDOWED=1 ~/proto-re/build/native/proto-native ~/proto-re-data/game
```

Прокси под Wine (папка игры из симлинков на установку, оригинальный `binkw32.dll` переименован в `binkw32_orig.dll`):

```sh
make -C src/host -j4
cp build/host/binkw32.dll ~/proto-re-data/game/
cd ~/proto-re-data/game && wine prototypef.exe
```

<details>
<summary>переменные для отладки</summary>

| переменная | |
|---|---|
| `PROTORE_NOHOOKS=1` | оригинальный движок через прокси |
| `PROTORE_NULLPATCH=1` | вернуть исходные байты на место хуков |
| `PROTORE_ONLY=a,b` / `PROTORE_SKIP=a,b` | фильтр хуков по префиксу имени |
| `PROTORE_DIFFTEST=1` / `=exit` | прогнать дифтесты (и выйти) |
| `PROTORE_DIFFTEST_RECOMP=1` | дифтесты перекомпилированного кода против оригинала |
| `PROTORE_RECOMP=1` / `=all` | тестовый набор / весь движок перекомпилирован |
| `PROTORE_RECOMP_RANGE=lo-hi` | перекомпилировать только этот диапазон адресов |
| `PROTORE_PROBE=openbykey\|watchdir` | пробы файловой системы |
| `PROTORE_TRACE_FPU=1` | трассировка вызовов CRT, зависящих от FPU |
| `PROTORE_WINDOWED=1` | ПК: в окне |
| `PROTORE_SHOT=300,2000` | сохранить эти кадры в `shot_<n>.ppm` |
| `PROTORE_INPUT=2100:Return,...` | нажать клавишу на нужном кадре |
| `PROTORE_TRACE_FILES=1` | логировать открытие файлов |

Поиск функции, которая ломает игру: `MIN_FRAMES=64 RUN_SECONDS=70 [START_LO=N] tools/recomp/bisect.sh`

</details>

## Тестирование

Спасибо Copyright-1 за тесты на Adreno.

## Прочее

Русский это фанатский перевод от ElArchibald: xdelta-патчи для 8 архивов RCF (субтитры, текст, HUD, шрифты).
Русской озвучки для PC-версии нет.

Файлов игры, оригинального DLL, декомпилированного и сгенерированного кода в репозитории нет и не будет.
DRM не обходится. Игра нужна своя, APK и файлы игры я не раздаю.

Prototype принадлежит Activision / Radical Entertainment.
