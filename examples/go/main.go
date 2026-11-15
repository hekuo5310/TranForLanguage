package main

import t "tranforcpp"

func main() {
	t.On("PlayerJoin", func(args []string) {
		t.SendMessage(args[0], "Welcome from Go!")
	})
	t.Run()
}
