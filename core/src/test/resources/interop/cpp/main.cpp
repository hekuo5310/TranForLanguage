#include <cstdlib>
#include <cstring>
int main() {
    const std::string self = std::getenv("TRANFORLANGUAGE_PROGRAM_ID");
    tranforlanguage::handle("echo", [&](const tranforlanguage::Args& a) { return self + ":" + (a.empty() ? "" : a[0]); });
    tranforlanguage::handle("bounce", [](const tranforlanguage::Args& a) { return tranforlanguage::call(a.at(0), "echo", {a.at(1)}); });
    return tranforlanguage::run([&](const tranforlanguage_event& e) {
        std::string name = e.event;
        tranforlanguage::Args args(e.args, e.args + e.argc);
        bool mine = !args.empty() && args[0] == self;
        if (name == "Ping" && mine) broadcast((self + "->" + tranforlanguage::call(args[1], "echo", {self})).c_str());
        else if (name == "Nested" && mine) broadcast(("nested " + self + "=" + tranforlanguage::call(args[1], "bounce", {self, self})).c_str());
        else if (name == "Missing" && mine) {
            try { tranforlanguage::call("nobody", "echo"); }
            catch (const tranforlanguage::CallError& error) { broadcast(("missing " + self + ":" + error.what()).c_str()); }
        } else if (name == "Announce" && mine) tranforlanguage::publish("greet", {"hi"});
        else if (name == "message" && e.topic && std::strcmp(e.topic, "greet") == 0)
            broadcast(("greet " + self + " from " + e.from + ": " + args.at(0)).c_str());
    });
}
