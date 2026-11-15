package main

import t "tranforlanguage"

func main() {
	me := t.ProgramID
	t.Handle("echo", func(args []string) (string, error) {
		value := ""
		if len(args) > 0 {
			value = args[0]
		}
		return me + ":" + value, nil
	})
	t.Handle("bounce", func(args []string) (string, error) { return t.Call(args[0], "echo", args[1]) })
	t.On("Ping", func(args []string) {
		if args[0] == me {
			result, _ := t.Call(args[1], "echo", me)
			t.Broadcast(me + "->" + result)
		}
	})
	t.On("Nested", func(args []string) {
		if args[0] == me {
			result, _ := t.Call(args[1], "bounce", me, me)
			t.Broadcast("nested " + me + "=" + result)
		}
	})
	t.On("Missing", func(args []string) {
		if args[0] == me {
			_, err := t.Call("nobody", "echo")
			t.Broadcast("missing " + me + ":" + err.Error())
		}
	})
	t.On("Announce", func(args []string) {
		if args[0] == me {
			t.Publish("greet", "hi")
		}
	})
	t.Subscribe("greet", func(from string, args []string) { t.Broadcast("greet " + me + " from " + from + ": " + args[0]) })
	t.Run()
}
