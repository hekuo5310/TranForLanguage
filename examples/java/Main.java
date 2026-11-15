import tranforcpp.Tranfor;

public class Main {
    public static void main(String[] args) {
        Tranfor.on("PlayerJoin", event -> Tranfor.sendMessage(event[0], "Welcome from Java!"));
        Tranfor.run();
    }
}
