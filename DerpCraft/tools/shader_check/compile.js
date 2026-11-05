// Compiles and links DerpCraft shader programs in headless Chromium (WebGL2 / GLSL ES 3.00).
// Usage: node compile.js <shaders dir>
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const dir = process.argv[2];

function load(file) {
  const src = fs.readFileSync(path.join(dir, file), 'utf8');
  return src.split('\n').map(l => {
    const t = l.trim();
    if (t.startsWith('#include')) return load(t.substring(t.indexOf('"') + 1, t.lastIndexOf('"')));
    return l;
  }).join('\n');
}
const PREC = 'precision highp float; precision highp int; precision highp sampler2DArray; precision highp usampler3D;' +
  ' precision highp sampler2DArrayShadow; precision highp sampler3D;';
function es(src, defines) {
  const nl = src.indexOf('\n');
  return '#version 300 es\n' + PREC + '\n' + (defines ? defines + '\n' : '') + src.substring(nl + 1);
}
const programs = [
  ['terrain', 'terrain', ''], ['terrain', 'terrain', '#define ALPHA_TEST'],
  ['shadow', 'shadow', ''], ['shadow', 'shadow', '#define ALPHA_TEST'],
  ['post', 'post_ssr', ''], ['post', 'post_godrays', ''], ['post', 'post_blur', ''], ['post', 'post_copy', ''], ['post', 'post_final', ''], ['post', 'post_ssao', ''], ['post', 'post_ssao_apply', ''], ['model', 'model', ''],
];
(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  await page.setContent('<canvas id=c></canvas>');
  const jobs = programs.map(([v, f, d]) => ({ name: `${v}/${f} ${d}`, vs: es(load(v + '.vsh'), d), fs: es(load(f + '.fsh'), d) }));
  const res = await page.evaluate((jobs) => {
    const gl = document.getElementById('c').getContext('webgl2');
    if (!gl) return [{ name: 'webgl2', log: 'no WebGL2' }];
    return jobs.map(j => {
      const out = { name: j.name, log: '' };
      const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) out.log += (type == gl.VERTEX_SHADER ? 'VS: ' : 'FS: ') + gl.getShaderInfoLog(s); return s; };
      const p = gl.createProgram();
      gl.attachShader(p, sh(gl.VERTEX_SHADER, j.vs)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, j.fs));
      gl.linkProgram(p);
      if (!out.log && !gl.getProgramParameter(p, gl.LINK_STATUS)) out.log += 'LINK: ' + gl.getProgramInfoLog(p);
      return out;
    });
  }, jobs);
  let bad = 0;
  for (const r of res) { console.log((r.log ? 'FAIL ' : 'ok   ') + r.name + (r.log ? '\n' + r.log : '')); if (r.log) bad++; }
  await browser.close();
  process.exit(bad ? 1 : 0);
})();
