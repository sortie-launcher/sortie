package io.github.esko997.sortie.shell;

import android.view.KeyEvent;

interface IShellService {
    String exec(String cmd) = 1;
    String restorePinnedTask(int rootTaskId) = 2;
    String injectKeyEvents(int displayId, in KeyEvent[] events) = 3;
    void destroy() = 16777114; // Shizuku UserService convention
}
