// No tranforlanguage.hpp needed. SDK functions are injected at compile time.
int main() {
    broadcast("Hello from C++!");
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.find("\"shutdown\"") != std::string::npos) break;
    }
}
