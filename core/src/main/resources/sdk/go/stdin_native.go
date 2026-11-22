//go:build !wasip1

package tranforlanguage

import (
	"io"
	"os"
)

// stdin is the protocol input. A native process reads it directly; blocking only parks this goroutine.
func stdin() io.Reader { return os.Stdin }
