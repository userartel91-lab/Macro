# shader_check — проверка шейдеров без видеокарты

Работает в облаке Claude (headless Chromium из Playwright, WebGL2 на SwiftShader). Шейдеры переводятся из
`#version 330 core` в `#version 300 es` (+ `precision`), `#include` подставляются как в `Shader.load`.

- `node tools/shader_check/compile.js src/main/resources/shaders` — компилирует и линкует программы мира, теней и
  постобработки (terrain ±ALPHA_TEST, shadow, post_ssr, post_godrays, post_blur, post_copy). Код выхода 1 при ошибке.
  Новый шейдер — добавь пару в список `programs`.
- `node tools/shader_check/render.js src/main/resources/shaders out.png [yaw] [pitch] [none|ssr|rays|both]` —
  синтетическая сцена лучами (вода с волнами из `terrain.fsh` `waterNormal`, глянцевый пол в клетку, коробки, небо
  с солнцем) с той же матрицей, что в `Session`, и настоящими `post_ssr.fsh`, `post_godrays.fsh` (экранный режим) и
  `post_blur.fsh`. Солнце в кадре: `yaw 180 pitch -6`. Объёмный режим лучей (по картам теней) здесь не проверяется.
