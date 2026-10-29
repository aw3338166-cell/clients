package apex.module;

public abstract class Module {
    public final String name;
    public final int key;
    public boolean enabled;
    public boolean keyWasDown;

    protected Module(String name, int key) { this.name = name; this.key = key; }

    public void toggle() { enabled = !enabled; if (enabled) onEnable(); else onDisable(); }
    public void onEnable() {}
    public void onDisable() {}
    public void onTick() {}
}
