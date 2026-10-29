package apex.module;

import org.lwjgl.glfw.GLFW;

/** Scales incoming knockback. Applied in VelocityMixin. */
public class Velocity extends Module {
    public double horizontal = 0.0;   // 0 = none, 1 = vanilla
    public double vertical = 0.0;

    public Velocity() { super("Velocity", GLFW.GLFW_KEY_K); }
}
