from tranforcpp import on, run, send_message


@on("PlayerJoin")
def player_joined(player):
    send_message(player, "Welcome from Python!")


run()
