import tranforcpp.Tranfor;

public class Main {
    public static void main(String[] ignored) {
        String me = Tranfor.programId();
        Tranfor.handle("echo", args -> me + ":" + (args.length > 0 ? args[0] : ""));
        Tranfor.handle("bounce", args -> Tranfor.call(args[0], "echo", args[1]));
        Tranfor.on("Ping", args -> { if (args[0].equals(me)) Tranfor.broadcast(me + "->" + Tranfor.call(args[1], "echo", me)); });
        Tranfor.on("Nested", args -> { if (args[0].equals(me)) Tranfor.broadcast("nested " + me + "=" + Tranfor.call(args[1], "bounce", me, me)); });
        Tranfor.on("Missing", args -> {
            if (!args[0].equals(me)) return;
            try { Tranfor.call("nobody", "echo"); } catch (Tranfor.CallError e) { Tranfor.broadcast("missing " + me + ":" + e.getMessage()); }
        });
        Tranfor.on("Announce", args -> { if (args[0].equals(me)) Tranfor.publish("greet", "hi"); });
        Tranfor.subscribe("greet", (from, args) -> Tranfor.broadcast("greet " + me + " from " + from + ": " + args[0]));
        Tranfor.run();
    }
}
