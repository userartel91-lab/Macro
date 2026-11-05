// Visual test of post_ssr.fsh, post_godrays.fsh (screen space mode), post_blur.fsh and the water normal of terrain.fsh
// on a synthetic ray-cast scene (WebGL2). Usage: node render.js <shaders dir> <out.png> [yawDeg] [pitchDeg] [mode]
// mode: none | ao | ssr | rays | both (ao + ssr + rays)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const dir = process.argv[2], outFile = process.argv[3];
const yaw = parseFloat(process.argv[4] || '0'), pitch = parseFloat(process.argv[5] || '-12'), mode = process.argv[6] || 'both';

function load(file) {
  return fs.readFileSync(path.join(dir, file), 'utf8').split('\n').map(l => {
    const t = l.trim();
    return t.startsWith('#include') ? load(t.substring(t.indexOf('"') + 1, t.lastIndexOf('"'))) : l;
  }).join('\n');
}
const PREC = 'precision highp float; precision highp int; precision highp sampler2DArray; precision highp usampler3D; precision highp sampler2DArrayShadow; precision highp sampler3D;';
const es = s => '#version 300 es\n' + PREC + '\n' + s.substring(s.indexOf('\n') + 1);
// the water normal function, taken from terrain.fsh as it is
const tf = fs.readFileSync(path.join(dir, 'terrain.fsh'), 'utf8');
const wn = tf.substring(tf.indexOf('const float WATER_K'), tf.indexOf('vec2 octEncode'));
const oct = tf.substring(tf.indexOf('vec2 octEncode'), tf.indexOf('float ign('));

const SCENE = `#version 300 es
${PREC}
in vec2 vUV;
uniform mat4 uInvProjView, uProjView;
uniform float uWaveTime, uWater;
uniform vec3 uSun;
layout(location = 0) out vec4 oColor;
layout(location = 1) out vec4 oMat;
${wn}
${oct}
bool box(vec3 ro, vec3 rd, vec3 lo, vec3 hi, out float t, out vec3 n) {
  vec3 inv = 1.0 / rd; vec3 a = (lo - ro) * inv, b = (hi - ro) * inv;
  vec3 mn = min(a, b), mx = max(a, b);
  float t0 = max(max(mn.x, mn.y), mn.z), t1 = min(min(mx.x, mx.y), mx.z);
  if (t1 < max(t0, 0.0)) return false;
  t = t0; n = -sign(rd) * step(mn.yzx, mn) * step(mn.zxy, mn); return true;
}
void main() {
  vec4 w = uInvProjView * vec4(vUV * 2.0 - 1.0, 1.0, 1.0);
  vec3 rd = normalize(w.xyz / w.w);
  float best = 1e9; vec3 n = vec3(0); vec3 col = vec3(0); float kind = 0.0;
  float t; vec3 bn;
  vec3 boxes[4] = vec3[](vec3(-4.0, -1.6, -12.0), vec3(3.0, -1.6, -16.0), vec3(-1.0, -1.6, -24.0), vec3(6.0, -1.6, -8.0));
  vec3 sizes[4] = vec3[](vec3(2.0, 3.0, 2.0), vec3(1.0, 9.0, 1.0), vec3(5.0, 5.0, 1.0), vec3(1.0, 2.0, 1.0));
  vec3 cols[4] = vec3[](vec3(0.8, 0.15, 0.1), vec3(0.2, 0.7, 0.2), vec3(0.9, 0.8, 0.3), vec3(0.3, 0.3, 0.9));
  for (int i = 0; i < 4; i++) if (box(vec3(0), rd, boxes[i], boxes[i] + sizes[i], t, bn) && t < best) {
    best = t; n = bn; col = cols[i] * (0.35 + 0.65 * max(dot(bn, uSun), 0.0)); kind = 0.0;
  }
  if (rd.y < 0.0) {
    float tp = -1.6 / rd.y;
    if (tp < best) {
      best = tp; vec3 p = rd * tp; n = vec3(0, 1, 0);
      if (p.x < 0.0) { // water: waves from terrain.fsh
        vec2 fw = fwidth(p.xz); n = waterNormal(p.xz + vec2(512.0), tp, max(fw.x, fw.y));
        col = vec3(0.05, 0.14, 0.3); kind = 64.0;
      } else { // checker concrete, glossy
        float c = mod(floor(p.x) + floor(p.z), 2.0);
        col = mix(vec3(0.55), vec3(0.75), c) * (0.4 + 0.6 * uSun.y); kind = 9.0;
      }
    }
  }
  if (best > 1e8) {
    vec3 sky = mix(vec3(0.75, 0.82, 0.95), vec3(0.35, 0.55, 0.95), clamp(rd.y * 2.0, 0.0, 1.0));
    sky += vec3(1.0, 0.9, 0.7) * pow(max(dot(rd, uSun), 0.0), 800.0) * 8.0;
    oColor = vec4(sky, 1.0); oMat = vec4(0.0); gl_FragDepth = 1.0; return;
  }
  vec3 p = rd * best;
  vec4 c = uProjView * vec4(p, 1.0);
  gl_FragDepth = c.z / c.w * 0.5 + 0.5;
  oColor = vec4(col, 1.0);
  oMat = vec4(octEncode(n), kind > 0.0 ? kind + 0.9 : 0.0, length(p));
}`;
const SHOW = `#version 300 es
${PREC}
in vec2 vUV; uniform sampler2D uSrc; out vec4 o;
void main() { vec3 c = texture(uSrc, vUV).rgb; o = vec4(pow(c / (1.0 + c * 0.25), vec3(1.0 / 1.2)), 1.0); }`;

(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  await page.setContent('<canvas id=c width=640 height=360></canvas>');
  const src = { post: es(load('post.vsh')), scene: SCENE, ssr: es(load('post_ssr.fsh')), rays: es(load('post_godrays.fsh')),
    blur: es(load('post_blur.fsh')), ao: es(load('post_ssao.fsh')), aoApply: es(load('post_ssao_apply.fsh')), copy: es(load('post_copy.fsh')), show: SHOW };
  const err = await page.evaluate(({ src, yaw, pitch, mode }) => {
    const cv = document.getElementById('c');
    const gl = cv.getContext('webgl2', { preserveDrawingBuffer: true });
    gl.getExtension('EXT_color_buffer_float');
    const W = cv.width, H = cv.height;
    const prog = (fs) => {
      const p = gl.createProgram();
      for (const [t, s] of [[gl.VERTEX_SHADER, src.post], [gl.FRAGMENT_SHADER, fs]]) {
        const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
        if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh));
        gl.attachShader(p, sh);
      }
      gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
      return p;
    };
    const U = (p, n) => gl.getUniformLocation(p, n);
    const tex = (fmt, w, h, f, ty) => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texImage2D(gl.TEXTURE_2D, 0, fmt, w, h, 0, f, ty, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE); return t; };
    const fbo = (cols, depth) => { const f = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, f);
      cols.forEach((c, i) => gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, c, 0));
      if (depth) gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT, gl.TEXTURE_2D, depth, 0);
      const st = gl.checkFramebufferStatus(gl.FRAMEBUFFER); if (st != gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo ' + st); return f; };
    // matrices exactly like Session: perspective * rotX(pitch) * rotY(yaw + 180)
    const mul = (a, b) => { const r = new Float32Array(16); for (let c = 0; c < 4; c++) for (let row = 0; row < 4; row++)
      r[c * 4 + row] = a[row] * b[c * 4] + a[4 + row] * b[c * 4 + 1] + a[8 + row] * b[c * 4 + 2] + a[12 + row] * b[c * 4 + 3]; return r; };
    const rot = (ang, x, y, z) => { const c = Math.cos(ang), s = Math.sin(ang), t = 1 - c, q = new Float32Array(16);
      q[0] = t*x*x + c; q[4] = t*x*y - s*z; q[8] = t*x*z + s*y; q[1] = t*x*y + s*z; q[5] = t*y*y + c; q[9] = t*y*z - s*x;
      q[2] = t*x*z - s*y; q[6] = t*y*z + s*x; q[10] = t*z*z + c; q[15] = 1; return q; };
    const near = 0.05, far = 512, f = 1 / Math.tan(70 * Math.PI / 360);
    const P = new Float32Array(16); P[0] = f / (W / H); P[5] = f; P[10] = (far + near) / (near - far); P[11] = -1; P[14] = 2 * far * near / (near - far);
    const view = mul(rot(pitch * Math.PI / 180, 1, 0, 0), rot((yaw + 180) * Math.PI / 180, 0, 1, 0));
    const PV = mul(P, view);
    const inv = (m) => { // general 4x4 inverse
      const a = m, o = new Float32Array(16);
      const b00 = a[0]*a[5]-a[1]*a[4], b01 = a[0]*a[6]-a[2]*a[4], b02 = a[0]*a[7]-a[3]*a[4], b03 = a[1]*a[6]-a[2]*a[5], b04 = a[1]*a[7]-a[3]*a[5], b05 = a[2]*a[7]-a[3]*a[6];
      const b06 = a[8]*a[13]-a[9]*a[12], b07 = a[8]*a[14]-a[10]*a[12], b08 = a[8]*a[15]-a[11]*a[12], b09 = a[9]*a[14]-a[10]*a[13], b10 = a[9]*a[15]-a[11]*a[13], b11 = a[10]*a[15]-a[11]*a[14];
      const d = 1 / (b00*b11 - b01*b10 + b02*b09 + b03*b08 - b04*b07 + b05*b06);
      o[0]=(a[5]*b11-a[6]*b10+a[7]*b09)*d; o[1]=(a[2]*b10-a[1]*b11-a[3]*b09)*d; o[2]=(a[13]*b05-a[14]*b04+a[15]*b03)*d; o[3]=(a[10]*b04-a[9]*b05-a[11]*b03)*d;
      o[4]=(a[6]*b08-a[4]*b11-a[7]*b07)*d; o[5]=(a[0]*b11-a[2]*b08+a[3]*b07)*d; o[6]=(a[14]*b02-a[12]*b05-a[15]*b01)*d; o[7]=(a[8]*b05-a[10]*b02+a[11]*b01)*d;
      o[8]=(a[4]*b10-a[5]*b08+a[7]*b06)*d; o[9]=(a[1]*b08-a[0]*b10-a[3]*b06)*d; o[10]=(a[12]*b04-a[13]*b02+a[15]*b00)*d; o[11]=(a[9]*b02-a[8]*b04-a[11]*b00)*d;
      o[12]=(a[5]*b07-a[4]*b09-a[6]*b06)*d; o[13]=(a[0]*b09-a[1]*b07+a[2]*b06)*d; o[14]=(a[13]*b01-a[12]*b03-a[14]*b00)*d; o[15]=(a[8]*b03-a[9]*b01+a[10]*b00)*d;
      return o; };
    const IPV = inv(PV);
    const sun = [-0.25, 0.22, -0.94]; const sl = Math.hypot(...sun); sun.forEach((v, i) => sun[i] = v / sl);
    const color = tex(gl.RGBA16F, W, H, gl.RGBA, gl.HALF_FLOAT), mat = tex(gl.RGBA16F, W, H, gl.RGBA, gl.HALF_FLOAT);
    gl.bindTexture(gl.TEXTURE_2D, mat); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    const depth = tex(gl.DEPTH_COMPONENT24, W, H, gl.DEPTH_COMPONENT, gl.UNSIGNED_INT);
    gl.bindTexture(gl.TEXTURE_2D, depth); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    const sceneF = fbo([color, mat], depth);
    const tmp = tex(gl.RGBA16F, W, H, gl.RGBA, gl.HALF_FLOAT), tmpF = fbo([tmp]);
    const hw = W / 2, hh = H / 2;
    const ha = tex(gl.RGBA16F, hw, hh, gl.RGBA, gl.HALF_FLOAT), haF = fbo([ha]), hb = tex(gl.RGBA16F, hw, hh, gl.RGBA, gl.HALF_FLOAT), hbF = fbo([hb]);
    const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
    // scene
    gl.bindFramebuffer(gl.FRAMEBUFFER, sceneF); gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1]); gl.viewport(0, 0, W, H);
    gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.ALWAYS);
    let p = prog(src.scene); gl.useProgram(p);
    gl.uniformMatrix4fv(U(p, 'uInvProjView'), false, IPV); gl.uniformMatrix4fv(U(p, 'uProjView'), false, PV);
    gl.uniform1f(U(p, 'uWaveTime'), 3.0); gl.uniform1f(U(p, 'uWater'), 1.0); gl.uniform3fv(U(p, 'uSun'), sun);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.disable(gl.DEPTH_TEST); gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
    const bind = (unit, t) => { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, t); };
    if (mode == 'ao' || mode == 'both' || mode == 'all') {
      gl.bindFramebuffer(gl.FRAMEBUFFER, haF); gl.viewport(0, 0, hw, hh);
      p = prog(src.ao); gl.useProgram(p); bind(1, depth); gl.uniform1i(U(p, 'uDepth'), 1);
      gl.uniformMatrix4fv(U(p, 'uProjView'), false, PV); gl.uniformMatrix4fv(U(p, 'uInvProjView'), false, IPV);
      gl.uniform1f(U(p, 'uNear'), near); gl.uniform1f(U(p, 'uFar'), far); gl.uniform2f(U(p, 'uTexel'), 1 / W, 1 / H); gl.uniform1f(U(p, 'uRadius'), 1.0);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      gl.bindFramebuffer(gl.FRAMEBUFFER, sceneF); gl.viewport(0, 0, W, H);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT, gl.TEXTURE_2D, null, 0);
      gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ZERO, gl.SRC_COLOR, gl.ZERO, gl.ONE);
      p = prog(src.aoApply); gl.useProgram(p); bind(0, ha); bind(1, depth);
      gl.uniform1i(U(p, 'uAO'), 0); gl.uniform1i(U(p, 'uDepth'), 1); gl.uniform1f(U(p, 'uNear'), near); gl.uniform1f(U(p, 'uFar'), far);
      gl.uniform1f(U(p, 'uStrength'), mode == 'ao' ? 1.0 : 1.0);
      gl.drawArrays(gl.TRIANGLES, 0, 3); gl.disable(gl.BLEND);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT, gl.TEXTURE_2D, depth, 0);
    }
    if (mode == 'ssr' || mode == 'both') {
      gl.bindFramebuffer(gl.FRAMEBUFFER, tmpF);
      p = prog(src.ssr); gl.useProgram(p);
      bind(2, mat); bind(1, depth); bind(0, color);
      gl.uniform1i(U(p, 'uScene'), 0); gl.uniform1i(U(p, 'uDepth'), 1); gl.uniform1i(U(p, 'uMaterial'), 2);
      gl.uniformMatrix4fv(U(p, 'uProjView'), false, PV); gl.uniformMatrix4fv(U(p, 'uInvProjView'), false, IPV);
      gl.uniform1f(U(p, 'uNear'), near); gl.uniform1f(U(p, 'uFar'), far);
      gl.uniform3f(U(p, 'uSkyColor'), 0.45, 0.62, 1.0); gl.uniform3f(U(p, 'uFogColor'), 0.75, 0.82, 0.95);
      gl.uniform3f(U(p, 'uSunColor'), 1, 0.9, 0.75); gl.uniform3fv(U(p, 'uLightDir'), sun);
      gl.uniform1f(U(p, 'uLightStrength'), 1); gl.uniform1f(U(p, 'uBlocks'), 1); gl.uniform1f(U(p, 'uWater'), 1); gl.uniform2f(U(p, 'uFog'), 300, 500);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      gl.bindFramebuffer(gl.FRAMEBUFFER, sceneF);
      gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ZERO, gl.ONE);
      p = prog(src.copy); gl.useProgram(p); gl.uniform1i(U(p, 'uSrc'), 0); bind(0, tmp);
      gl.drawArrays(gl.TRIANGLES, 0, 3); gl.disable(gl.BLEND);
    }
    if (mode == 'rays' || mode == 'both') {
      // sun on screen like PostProcessor.screenEffects
      const s4 = [0, 1, 2, 3].map(r => PV[r] * sun[0] * 1000 + PV[4 + r] * sun[1] * 1000 + PV[8 + r] * sun[2] * 1000 + PV[12 + r]);
      let su = 0.5, sv = 0.5, fade = 0;
      if (s4[3] > 0) { su = s4[0] / s4[3] * 0.5 + 0.5; sv = s4[1] / s4[3] * 0.5 + 0.5;
        const out = Math.max(-su, su - 1, -sv, sv - 1); fade = 1 - Math.max(0, Math.min(1, out / 0.35)); }
      gl.bindFramebuffer(gl.FRAMEBUFFER, haF); gl.viewport(0, 0, hw, hh);
      p = prog(src.rays); gl.useProgram(p); bind(1, depth); gl.uniform1i(U(p, 'uDepth'), 1);
      gl.uniformMatrix4fv(U(p, 'uInvProjView'), false, IPV); gl.uniform1i(U(p, 'uMode'), 0);
      gl.uniform2f(U(p, 'uSunUV'), su, sv); gl.uniform1f(U(p, 'uSunFade'), fade); gl.uniform1f(U(p, 'uAspect'), W / H);
      gl.uniform3f(U(p, 'uSunColor'), 1, 0.9, 0.75); gl.uniform1f(U(p, 'uLightStrength'), 1); gl.uniform1i(U(p, 'uCascades'), 0);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      p = prog(src.blur); gl.useProgram(p); gl.uniform1i(U(p, 'uSrc'), 0);
      gl.bindFramebuffer(gl.FRAMEBUFFER, hbF); bind(0, ha); gl.uniform2f(U(p, 'uStep'), 1.5 / hw, 0); gl.uniform1f(U(p, 'uGain'), 1);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      gl.bindFramebuffer(gl.FRAMEBUFFER, sceneF); gl.viewport(0, 0, W, H);
      gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ONE, gl.ONE, gl.ZERO, gl.ONE);
      bind(0, hb); gl.uniform2f(U(p, 'uStep'), 0, 1.5 / hh); gl.uniform1f(U(p, 'uGain'), 1);
      gl.drawArrays(gl.TRIANGLES, 0, 3); gl.disable(gl.BLEND);
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, W, H);
    p = prog(src.show); gl.useProgram(p); gl.uniform1i(U(p, 'uSrc'), 0); bind(0, color);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    return gl.getError();
  }, { src, yaw, pitch, mode });
  if (err) console.log('GL error', err);
  await page.locator('#c').screenshot({ path: outFile });
  await browser.close();
})().catch(e => { console.error(e.message); process.exit(1); });
