import tranforlanguage.TranForLanguage;

public class Main {
    public static void main(String[] args) {
        TranForLanguage.on("PlayerJoin", event -> TranForLanguage.sendMessage(event[0], "Welcome from Java!"));
        TranForLanguage.run();
    }
}
