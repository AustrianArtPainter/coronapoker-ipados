"""Route desktop OpenGL calls to the current libGDX GLES context."""
import re


def card_shell(source):
    source = re.sub(r"^import org.lwjgl.opengl.[^;]+;\n", "", source, flags=re.M)
    source = source.replace("    private final IntBuffer parent", """    private final IntBuffer integerQuery = BufferUtils.newIntBuffer(1);

    private int readInteger(int parameter) {
        Gdx.gl.glGetIntegerv(parameter, integerQuery);
        return integerQuery.get(0);
    }

    private final IntBuffer parent""")
    source = source.replace("!GL.getCapabilities().OpenGL30", "Gdx.gl30 == null")
    source = source.replace("GL11C.glGetInteger(", "readInteger(")
    source = source.replace("GL11C.glIsEnabled(", "Gdx.gl.glIsEnabled(")
    source = source.replace("GL11C.glGetIntegerv(", "Gdx.gl.glGetIntegerv(")
    # GLES enables multisampling per attachment. Desktop GL_MULTISAMPLE is
    # not a supported enable/disable capability on this context.
    source = "\n".join(line for line in source.split("\n") if "GL13C.GL_MULTISAMPLE" not in line and "boolean previousMultisample" not in line)
    for function in ("GenFramebuffers", "GenRenderbuffers", "DeleteFramebuffers", "DeleteRenderbuffers"):
        source = source.replace("GL30C.gl" + function + "(", "Gdx.gl.gl" + function[:-1] + "(")
    source = source.replace("GL30C.gl", "Gdx.gl30.gl")
    source = source.replace("GL30C.GL_", "com.badlogic.gdx.graphics.GL30.GL_")
    source = source.replace("GL11C.GL_RGBA8", "com.badlogic.gdx.graphics.GL30.GL_RGBA8")
    return source.replace("GL13C.GL_SAMPLES", "GL20.GL_SAMPLES")
