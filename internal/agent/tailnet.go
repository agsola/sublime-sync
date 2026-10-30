package agent

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"time"
)

var tailnetPrefix = netip.MustParsePrefix("100.64.0.0/10")

// pickTailnet returns the first IPv4 address in Tailscale's CGNAT range.
func pickTailnet(addrs []netip.Addr) (netip.Addr, bool) {
	for _, a := range addrs {
		a = a.Unmap()
		if a.Is4() && tailnetPrefix.Contains(a) {
			return a, true
		}
	}
	return netip.Addr{}, false
}

// TailscaleAddr finds this machine's Tailscale IPv4 address, if Tailscale is up.
func TailscaleAddr() (netip.Addr, bool) {
	ifaces, err := net.InterfaceAddrs()
	if err != nil {
		return netip.Addr{}, false
	}
	var addrs []netip.Addr
	for _, ia := range ifaces {
		if p, err := netip.ParsePrefix(ia.String()); err == nil {
			addrs = append(addrs, p.Addr())
		}
	}
	return pickTailnet(addrs)
}

// ServeTailnet serves h only on the Tailscale address, re-checking every
// interval: it starts when Tailscale comes up, moves if the address changes
// and stops when Tailscale goes down. It never listens on other interfaces.
func ServeTailnet(ctx context.Context, h http.Handler, port int, interval time.Duration) {
	var cur netip.Addr
	var srv *http.Server
	stop := func() {
		if srv != nil {
			sctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			srv.Shutdown(sctx)
			cancel()
			srv = nil
			cur = netip.Addr{}
		}
	}
	defer stop()
	for {
		addr, ok := TailscaleAddr()
		if !ok && srv != nil {
			log.Printf("tailnet: Tailscale down, stopping phone API")
			stop()
		}
		if ok && addr != cur {
			stop()
			listen := net.JoinHostPort(addr.String(), strconv.Itoa(port))
			ln, err := net.Listen("tcp", listen)
			if err != nil {
				log.Printf("tailnet: %v", err)
			} else {
				srv = &http.Server{Handler: h, ReadHeaderTimeout: 10 * time.Second}
				cur = addr
				log.Printf("tailnet: phone API on %s", listen)
				go func(s *http.Server) {
					if err := s.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
						log.Printf("tailnet: %v", err)
					}
				}(srv)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(interval):
		}
	}
}
