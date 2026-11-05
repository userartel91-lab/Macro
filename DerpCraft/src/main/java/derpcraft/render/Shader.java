package derpcraft.render;

import derpcraft.util.Mat4;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL45C.*;

/** GLSL program loaded from /shaders/name.vsh + name.fsh on the classpath. */
public final class Shader {
    public final int id;
    private final Map<String, Integer> locs = new HashMap<>();

    public Shader(String name) { this(name, name, ""); }

    public Shader(String vsh, String fsh, String defines) {
        int v = compile(GL_VERTEX_SHADER, load(vsh + ".vsh"), defines, vsh);
        int f = compile(GL_FRAGMENT_SHADER, load(fsh + ".fsh"), defines, fsh);
        id = glCreateProgram();
        glAttachShader(id, v);
        glAttachShader(id, f);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE)
            throw new RuntimeException("Shader link failed (" + vsh + "/" + fsh + "): " + glGetProgramInfoLog(id));
        glDeleteShader(v);
        glDeleteShader(f);
    }

    /** Loads a shader file; lines like {@code #include "common.glsl"} are replaced by that file. */
    private static String load(String file) {
        String src;
        try (InputStream in = Shader.class.getResourceAsStream("/shaders/" + file)) {
            if (in == null) throw new RuntimeException("Missing shader " + file);
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new RuntimeException(e); }
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#include")) {
                String inc = t.substring(t.indexOf('"') + 1, t.lastIndexOf('"'));
                out.append(load(inc)).append('\n');
            } else out.append(line).append('\n');
        }
        return out.toString();
    }

    private static int compile(int type, String src, String defines, String name) {
        if (!defines.isEmpty()) {
            int nl = src.indexOf('\n');
            src = src.substring(0, nl + 1) + defines + "\n" + src.substring(nl + 1);
        }
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE)
            throw new RuntimeException("Shader compile failed (" + name + "): " + glGetShaderInfoLog(s));
        return s;
    }

    public Shader use() { glUseProgram(id); return this; }

    public int loc(String n) { return locs.computeIfAbsent(n, k -> glGetUniformLocation(id, k)); }

    public void set(String n, int v) { glUniform1i(loc(n), v); }
    public void set(String n, float v) { glUniform1f(loc(n), v); }
    public void set(String n, float x, float y) { glUniform2f(loc(n), x, y); }
    public void set(String n, float x, float y, float z) { glUniform3f(loc(n), x, y, z); }
    public void set(String n, float x, float y, float z, float w) { glUniform4f(loc(n), x, y, z, w); }
    public void seti(String n, int x, int y) { glUniform2i(loc(n), x, y); }
    public void seti3(String n, int x, int y, int z) { glUniform3i(loc(n), x, y, z); }
    public void set(String n, Mat4 m) { glUniformMatrix4fv(loc(n), false, m.m); }
    public void set3v(String n, float[] v) { glUniform3fv(loc(n), v); }
    public void set4v(String n, float[] v) { glUniform4fv(loc(n), v); }
}
