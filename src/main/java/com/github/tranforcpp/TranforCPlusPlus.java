package com.github.tranforcpp;

import com.github.tranforcpp.command.TranforCommand;
import com.github.tranforcpp.command.TranforTabCompleter;
import com.github.tranforcpp.listener.PluginListListener;
import com.github.tranforcpp.optimizer.MemoryOptimizer;
import com.github.tranforcpp.optimizer.SmartThreadOptimizer;
import com.github.tranforcpp.utils.AnsiColorUtils;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;

/**
 * TranforC++ 主插件类
 * <p>
 * 这是一个允许在Minecraft服务器上运行C++代码的插件。
 * 它提供了完整的C++插件生态系统，包括编译、执行和事件处理功能。
 * <p>
 * 主要特性：
 * - 自动编译C++源代码
 * - 跨语言事件通信
 * - 智能线程优化
 * - 内存管理优化
 * - 多服务器消息传递支持
 * 
 * @author hekuo, Xiao-QDev
 * @version 1.1.2
 */
public class TranforCPlusPlus extends JavaPlugin {

    private static TranforCPlusPlus instance;
    private ProcessManager processManager;
    private StartupManager startupManager;
    // 移除了代理端支持 - 不再需要 messagingManager 字段
    private MemoryOptimizer memoryOptimizer;
    private SmartThreadOptimizer threadOptimizer;
    private PluginListListener pluginListListener;

    @Override
    public void onEnable() {
        instance = this;
                getLogger().info(AnsiColorUtils.colorize("正在初始化TranforC++模块...", AnsiColorUtils.COLOR_51));
        // 注册主命令
        registerTranforCommand();
        
        // 初始化启动管理器
        startupManager = new StartupManager(this);
        startupManager.startAsync();
        
        // 初始化内存优化器
        memoryOptimizer = new MemoryOptimizer();
        memoryOptimizer.initialize();
        
        // 初始化智能线程优化器
        threadOptimizer = new SmartThreadOptimizer(this);
        threadOptimizer.initialize();
        
        // 初始化事件分发器
        com.github.tranforcpp.command.SmartEventDispatcher eventDispatcher = 
            new com.github.tranforcpp.command.SmartEventDispatcher();
        eventDispatcher.initialize();

        // 注册插件监听器
        this.pluginListListener = new PluginListListener();
        getServer().getPluginManager().registerEvents(this.pluginListListener, this);
    }
    
    private void registerTranforCommand() {
        org.bukkit.command.PluginCommand command = getCommand("tranforcpp");
        if (command == null) throw new IllegalStateException("Missing tranforcpp command in plugin.yml");
        command.setExecutor(new TranforCommand());
        command.setTabCompleter(new TranforTabCompleter());
    }

    @Override
    public void onDisable() {
        if (processManager != null) {
            processManager.stop();
        }
        if (startupManager != null) {
            startupManager.shutdown();
        }
        // 移除了代理端支持 - 不再清理消息管理器
        if (memoryOptimizer != null) {
            memoryOptimizer.shutdown();
        }
        if (threadOptimizer != null) {
            threadOptimizer.shutdown();
        }
        if (pluginListListener != null) {
            pluginListListener.shutdown();
        }
        getLogger().info(AnsiColorUtils.colorize("TranforC++模块插件 已关闭!", AnsiColorUtils.RED));
    }

    public static TranforCPlusPlus getInstance() {
        return instance;
    }

    public ProcessManager getProcessManager() {
        return processManager;
    }

    public void setProcessManager(ProcessManager processManager) {
        this.processManager = processManager;
    }

    public void reload() {
        if (processManager != null) {
            processManager.restart();
        }
    }
    public SmartThreadOptimizer getThreadOptimizer() {
        return threadOptimizer;
    }

}