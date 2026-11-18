import tranforlanguage.TranForLanguage;

public class Main {
    public static void main(String[] ignored) {
        String me = TranForLanguage.programId();
        TranForLanguage.handle("echo", args -> me + ":" + (args.length > 0 ? args[0] : ""));
        TranForLanguage.handle("bounce", args -> TranForLanguage.call(args[0], "echo", args[1]));
        TranForLanguage.on("Ping", args -> { if (args[0].equals(me)) TranForLanguage.broadcast(me + "->" + TranForLanguage.call(args[1], "echo", me)); });
        TranForLanguage.on("Nested", args -> { if (args[0].equals(me)) TranForLanguage.broadcast("nested " + me + "=" + TranForLanguage.call(args[1], "bounce", me, me)); });
        TranForLanguage.on("Missing", args -> {
            if (!args[0].equals(me)) return;
            try { TranForLanguage.call("nobody", "echo"); } catch (TranForLanguage.CallError e) { TranForLanguage.broadcast("missing " + me + ":" + e.getMessage()); }
        });
        TranForLanguage.on("Server", args -> { if (args[0].equals(me)) TranForLanguage.broadcast("server " + me + "=" + TranForLanguage.server("ping", me)); });
        TranForLanguage.on("Announce", args -> { if (args[0].equals(me)) TranForLanguage.publish("greet", "hi"); });
        TranForLanguage.subscribe("greet", (from, args) -> TranForLanguage.broadcast("greet " + me + " from " + from + ": " + args[0]));
        TranForLanguage.run();
    }
}
