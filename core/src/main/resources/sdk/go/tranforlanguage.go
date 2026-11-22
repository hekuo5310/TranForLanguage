// Package tranforlanguage is the protocol-v1 SDK for Go programs; the server installs it automatically.
// stdout belongs to the protocol; log diagnostics to stderr.
//
// Programs can call each other: Handle exposes a method, Call waits for its string result,
// Publish sends a "message" event to other programs. Call is safe from any goroutine.
package tranforlanguage

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"sync"
)

// Event is one line from the server. Game events only use Event and Args.
type Event struct {
	Event  string   `json:"event"`
	Args   []string `json:"args"`
	From   string   `json:"from,omitempty"`
	Topic  string   `json:"topic,omitempty"`
	ID     string   `json:"id,omitempty"`
	Method string   `json:"method,omitempty"`
	OK     bool     `json:"ok,omitempty"`
	Result string   `json:"result,omitempty"`
	Error  string   `json:"error,omitempty"`
}

// CallError reports a failed Call: unknown program or method, remote error, timeout, shutdown.
type CallError struct{ Message string }

func (e *CallError) Error() string { return e.Message }

// MethodFunc answers calls from other programs; a non-nil error is returned to the caller.
type MethodFunc func(args []string) (string, error)

var (
	outputMu sync.Mutex
	mu       sync.Mutex
	handlers = map[string][]func(args []string){}
	methods  = map[string]MethodFunc{}
	topics   = map[string][]func(from string, args []string){}
	waiting  = map[string]chan Event{}
	nextID   uint64
	closed   string
	once     sync.Once
	// An unbounded queue: the reader must never block, or replies to a Call made inside an
	// event handler could not be delivered.
	queueMu   sync.Mutex
	queueCond = sync.NewCond(&queueMu)
	queue     []Event
	queueDone bool
)

// ProgramID is this program's ID as the server knows it (directory name or "@root").
var ProgramID = os.Getenv("TRANFORLANGUAGE_PROGRAM_ID")

func send(fields map[string]interface{}) error {
	line, err := json.Marshal(fields)
	if err != nil {
		return err
	}
	outputMu.Lock()
	defer outputMu.Unlock()
	_, err = os.Stdout.Write(append(line, '\n'))
	return err
}

func Broadcast(message string) error {
	return send(map[string]interface{}{"action": "broadcast", "message": message})
}
func SendMessage(player, message string) error {
	return send(map[string]interface{}{"action": "sendMessage", "player": player, "message": message})
}
func Console(message string) error {
	return send(map[string]interface{}{"action": "console", "message": message})
}
func DispatchCommand(command string) error {
	return send(map[string]interface{}{"action": "executeCommand", "command": command})
}

// On registers a game-event handler, for example On("PlayerJoin", func(args []string) {...}).
func On(event string, handler func(args []string)) {
	mu.Lock()
	defer mu.Unlock()
	handlers[event] = append(handlers[event], handler)
}

// Handle exposes a method to other programs.
func Handle(method string, fn MethodFunc) {
	mu.Lock()
	defer mu.Unlock()
	methods[method] = fn
}

// Subscribe receives Publish(topic, ...) from other programs.
func Subscribe(topic string, handler func(from string, args []string)) {
	mu.Lock()
	defer mu.Unlock()
	topics[topic] = append(topics[topic], handler)
}

func strs(args []string) []string {
	if args == nil {
		return []string{}
	}
	return args
}

// Call invokes method on program target and waits for its string result.
func Call(target, method string, args ...string) (string, error) {
	start()
	mu.Lock()
	if closed != "" {
		mu.Unlock()
		return "", &CallError{closed}
	}
	nextID++
	id := strconv.FormatUint(nextID, 10)
	reply := make(chan Event, 1)
	waiting[id] = reply
	mu.Unlock()
	if err := send(map[string]interface{}{"action": "call", "target": target, "method": method, "args": strs(args), "id": id}); err != nil {
		mu.Lock()
		delete(waiting, id)
		mu.Unlock()
		return "", err
	}
	answer := <-reply
	if !answer.OK {
		if answer.Error == "" {
			answer.Error = "call failed"
		}
		return "", &CallError{answer.Error}
	}
	return answer.Result, nil
}

// Server calls a game action, for example Server("teleport", "Alex", "0", "64", "0").
func Server(method string, args ...string) (string, error) { return Call("@server", method, args...) }

// Notify is a one-way call with no reply.
func Notify(target, method string, args ...string) error {
	return send(map[string]interface{}{"action": "call", "target": target, "method": method, "args": strs(args)})
}

// Publish sends a "message" event with topic to every other program that accepts it.
func Publish(topic string, args ...string) error {
	return send(map[string]interface{}{"action": "publish", "topic": topic, "args": strs(args)})
}

func serve(e Event) {
	mu.Lock()
	fn := methods[e.Method]
	mu.Unlock()
	var result string
	var err error
	func() {
		defer func() {
			if r := recover(); r != nil {
				err = fmt.Errorf("panic: %v", r)
			}
		}()
		if fn == nil {
			err = errors.New("unknown method " + e.Method)
			return
		}
		result, err = fn(strs(e.Args))
	}()
	if e.ID == "" {
		if err != nil {
			fmt.Fprintln(os.Stderr, "tranforlanguage: notification failed:", err)
		}
		return
	}
	if err != nil {
		send(map[string]interface{}{"action": "reply", "id": e.ID, "error": err.Error()})
	} else {
		send(map[string]interface{}{"action": "reply", "id": e.ID, "result": result})
	}
}

func failAll(reason string) {
	mu.Lock()
	defer mu.Unlock()
	if closed == "" {
		closed = reason
	}
	for id, reply := range waiting {
		reply <- Event{Event: "reply", Error: reason}
		delete(waiting, id)
	}
}

func start() {
	once.Do(func() {
		go func() {
			scanner := bufio.NewScanner(stdin())
			scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
			for scanner.Scan() {
				var e Event
				if len(scanner.Bytes()) == 0 {
					continue
				}
				if err := json.Unmarshal(scanner.Bytes(), &e); err != nil {
					fmt.Fprintln(os.Stderr, "tranforlanguage: invalid protocol line:", err)
					continue
				}
				switch e.Event {
				case "call":
					go serve(e) // concurrent, so nested calls (A -> B -> A) never deadlock
				case "reply":
					mu.Lock()
					reply := waiting[e.ID]
					delete(waiting, e.ID)
					mu.Unlock()
					if reply != nil {
						reply <- e
					}
				default:
					if e.Event == "shutdown" {
						failAll("server is shutting down")
					}
					queueMu.Lock()
					queue = append(queue, e)
					queueCond.Signal()
					queueMu.Unlock()
				}
			}
			failAll("input closed")
			queueMu.Lock()
			queueDone = true
			queueCond.Broadcast()
			queueMu.Unlock()
		}()
	})
}

// Run dispatches events in order until shutdown or end of input.
func Run() error {
	start()
	for {
		queueMu.Lock()
		for len(queue) == 0 && !queueDone {
			queueCond.Wait()
		}
		if len(queue) == 0 {
			queueMu.Unlock()
			return nil
		}
		e := queue[0]
		queue = queue[1:]
		queueMu.Unlock()
		mu.Lock()
		eventHandlers := append([]func([]string){}, handlers[e.Event]...)
		topicHandlers := append([]func(string, []string){}, topics[e.Topic]...)
		mu.Unlock()
		if e.Event == "message" {
			for _, handler := range topicHandlers {
				handler(e.From, strs(e.Args))
			}
		}
		for _, handler := range eventHandlers {
			handler(strs(e.Args))
		}
		if e.Event == "shutdown" {
			return nil
		}
	}
}
