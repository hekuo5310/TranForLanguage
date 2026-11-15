// economy owns player balances. Other programs, in any language, call "balance" and "spend".
package main

import (
	"errors"
	"strconv"
	"sync"

	t "tranforcpp"
)

func main() {
	var mu sync.Mutex
	balances := map[string]int{}
	t.On("PlayerJoin", func(args []string) {
		mu.Lock()
		defer mu.Unlock()
		if _, ok := balances[args[0]]; !ok {
			balances[args[0]] = 100
		}
	})
	t.Handle("balance", func(args []string) (string, error) {
		mu.Lock()
		defer mu.Unlock()
		return strconv.Itoa(balances[args[0]]), nil
	})
	t.Handle("spend", func(args []string) (string, error) {
		amount, err := strconv.Atoi(args[1])
		if err != nil || amount <= 0 {
			return "", errors.New("invalid amount")
		}
		mu.Lock()
		defer mu.Unlock()
		if balances[args[0]] < amount {
			return "", errors.New("not enough coins")
		}
		balances[args[0]] -= amount
		return strconv.Itoa(balances[args[0]]), nil
	})
	t.Run()
}
