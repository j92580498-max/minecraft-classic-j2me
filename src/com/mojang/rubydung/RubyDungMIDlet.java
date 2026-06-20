package com.mojang.rubydung;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

public class RubyDungMIDlet extends MIDlet {
    private GameCanvas canvas;

    protected void startApp() {
        if (canvas == null) {
            canvas = new GameCanvas(this);
            Display.getDisplay(this).setCurrent(canvas);
            canvas.start();
        }
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean unconditional) {
        if (canvas != null) canvas.stop();
    }
}
