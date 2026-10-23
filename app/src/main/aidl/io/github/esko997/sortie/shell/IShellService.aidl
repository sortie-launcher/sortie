package io.github.esko997.sortie.shell;

interface IShellService {
    String exec(String cmd) = 1;
    void destroy() = 16777114; // Shizuku UserService convention
}
