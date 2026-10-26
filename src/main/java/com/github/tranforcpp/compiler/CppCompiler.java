package com.github.tranforcpp.compiler;

import com.github.tranforcpp.TranforCPlusPlus;
import java.io.File;

/** Backwards-compatible Bukkit facade for the shared compiler. */
public class CppCompiler extends com.github.tranforcpp.core.language.CppCompiler {
    public CppCompiler() {
        super(new File(TranforCPlusPlus.getInstance().getDataFolder().getParentFile(), "C++ Plugins"),
              TranforCPlusPlus.getInstance().getLogger());
    }
}
