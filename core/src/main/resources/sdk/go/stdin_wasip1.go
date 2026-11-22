//go:build wasip1

package tranforlanguage

import (
	"io"
	"os"
	"time"
)

// stdinReady is provided by the server when the program runs inside its JVM: 1 once a whole line
// (or the end of input) is buffered.
//
//go:wasmimport tranforlanguage stdin_ready
func stdinReady() int32

// politeStdin waits for input with time.Sleep instead of a blocking read. WebAssembly runs every
// goroutine on one thread, so a blocking read would stop all of them until the next event arrived.
type politeStdin struct{}

func (politeStdin) Read(p []byte) (int, error) {
	wait := time.Millisecond
	for stdinReady() == 0 {
		time.Sleep(wait)
		if wait < 20*time.Millisecond {
			wait *= 2
		}
	}
	return os.Stdin.Read(p)
}

func stdin() io.Reader { return politeStdin{} }
