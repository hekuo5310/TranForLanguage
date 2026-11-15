#include <cstdlib>
#include <cstring>
int main() {
    const std::string self = std::getenv("TRANFORCPP_PROGRAM_ID");
    tranforcpp::handle("echo", [&](const tranforcpp::Args& a) { return self + ":" + (a.empty() ? "" : a[0]); });
    tranforcpp::handle("bounce", [](const tranforcpp::Args& a) { return tranforcpp::call(a.at(0), "echo", {a.at(1)}); });
    return tranforcpp::run([&](const tranforcpp_event& e) {
        std::string name = e.event;
        tranforcpp::Args args(e.args, e.args + e.argc);
        bool mine = !args.empty() && args[0] == self;
        if (name == "Ping" && mine) broadcast((self + "->" + tranforcpp::call(args[1], "echo", {self})).c_str());
        else if (name == "Nested" && mine) broadcast(("nested " + self + "=" + tranforcpp::call(args[1], "bounce", {self, self})).c_str());
        else if (name == "Missing" && mine) {
            try { tranforcpp::call("nobody", "echo"); }
            catch (const tranforcpp::CallError& error) { broadcast(("missing " + self + ":" + error.what()).c_str()); }
        } else if (name == "Announce" && mine) tranforcpp::publish("greet", {"hi"});
        else if (name == "message" && e.topic && std::strcmp(e.topic, "greet") == 0)
            broadcast(("greet " + self + " from " + e.from + ": " + args.at(0)).c_str());
    });
}
