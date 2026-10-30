package proto

// Tab is one Sublime view, as sealed into a blob.
type Tab struct {
	ID     int64 `json:"id"`
	Window int64 `json:"window"`
	// WindowName is the Sublime project or first folder of the window, if any.
	WindowName string  `json:"windowName,omitempty"`
	Index      int     `json:"index"`
	Title      string  `json:"title"`
	Path       *string `json:"path"`
	Dirty      bool    `json:"dirty"`
	Syntax     string  `json:"syntax"`
	Text       string  `json:"text"`
	Truncated  bool    `json:"truncated"`
	// Modified is when the text last changed (unix ms): the file's mtime for
	// saved files, otherwise when the agent first saw the current text.
	Modified int64 `json:"modified"`
}

// TabRef points from a manifest to a sealed tab blob.
type TabRef struct {
	Blob   string `json:"blob"`
	Window int64  `json:"window"`
	Index  int    `json:"index"`
}

// Manifest describes one snapshot of all open tabs.
type Manifest struct {
	Seq     int64    `json:"seq"`
	Created int64    `json:"created"`
	Source  string   `json:"source"`
	Tabs    []TabRef `json:"tabs"`
}

const (
	SourcePlugin  = "plugin"
	SourceSession = "session"
)

// PairPayload is what the pairing QR code contains.
type PairPayload struct {
	V      int    `json:"v"`
	Key    string `json:"k"`
	Server string `json:"s"`
	Mac    string `json:"m"`
}

// MaxTabBytes is the per-tab size limit; longer texts are truncated.
const MaxTabBytes = 5 * 1024 * 1024
