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
	// UID is a stable id the plugin stores in the view's settings, so it
	// survives Sublime restarts (view ids don't).
	UID string `json:"uid,omitempty"`
	// Draft is the phone draft this "✎" tab shows, if any.
	Draft string `json:"draft,omitempty"`
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

// Command types sent from the phone to the Mac.
const (
	CommandDraft = "draft" // create or update the "✎" tab of a phone draft
	CommandClose = "close" // close a tab in Sublime if it is safe
)

// Command is sealed by the phone (AAD CommandAAD(id)) and applied by the plugin.
type Command struct {
	ID        string `json:"id"`
	Type      string `json:"type"`
	Created   int64  `json:"created"`
	DraftID   string `json:"draftId,omitempty"`
	SourceUID string `json:"sourceUid,omitempty"`
	Title     string `json:"title,omitempty"`
	Text      string `json:"text,omitempty"`
	Rev       int64  `json:"rev,omitempty"`
	UID       string `json:"uid,omitempty"`
}

// Result statuses.
const (
	ResultApplied  = "applied"
	ResultConflict = "conflict" // the "✎" tab changed on the Mac; a numbered copy was opened
	ResultRefused  = "refused"  // e.g. closing a tab with unsaved changes
	ResultRejected = "rejected" // replayed, too old or undecryptable
)

// Result is sealed by the Mac (AAD ResultAAD(id)).
type Result struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Reason string `json:"reason,omitempty"`
	UID    string `json:"uid,omitempty"`
	At     int64  `json:"at"`
}

// PhoneState holds per-tab flags shared by phones (AAD StateAAD("phone-state")).
type PhoneState struct {
	Entries map[string]PhoneEntry `json:"entries"`
}

type PhoneEntry struct {
	Hidden  bool  `json:"hidden"`
	Private bool  `json:"private"`
	At      int64 `json:"at"`
}

// MaxCommandAge is how old a command may be before the Mac rejects it.
const MaxCommandAge = 7 * 24 * 60 * 60 * 1000
