# shop sells items: "!buy <item>" in chat calls the Go economy program, then tells everyone.
from tranforcpp import CallError, call, handle, on, publish, run, send_message

PRICES = {"apple": 10, "sword": 60}


@handle("price")
def price(item):
    if item not in PRICES:
        raise KeyError(item)
    return PRICES[item]


@on("PlayerChat")
def chat(player, message):
    if not message.startswith("!buy "):
        return
    item = message[5:].strip()
    if item not in PRICES:
        send_message(player, "Unknown item: " + item)
        return
    try:
        left = call("economy", "spend", player, PRICES[item])
    except CallError as error:
        send_message(player, "Cannot buy " + item + ": " + str(error))
        return
    send_message(player, "Bought %s, %s coins left" % (item, left))
    publish("purchase", player, item)


run()
