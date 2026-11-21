package local.net.iperf.core

data class RunError(val title: String, val detail: String, val raw: String? = null)

/** Turns iperf3's error strings into something a person can act on. */
object ErrorMapper {
    /** [usedLogin]: whether this run sent an iperf3 username/key (changes what a dropped control socket means). */
    fun map(raw: String, host: String, port: Int, usedLogin: Boolean = false): RunError {
        val m = raw.lowercase()
        val target = "$host:$port"
        return when {
            // iperf3 3.22 servers that reject a login just close the control socket; no "authorization failed" is sent.
            "control socket has closed unexpectedly" in m && usedLogin -> RunError(
                "Login rejected",
                "$target closed the connection right after the iperf3 login. The username, password or public key is " +
                    "probably wrong (check them under Edit server), or the phone's clock is more than about 10 s off.",
                raw,
            )
            "authorization failed" in m || "authentication failed" in m -> RunError(
                "Login rejected",
                "$target refused the iperf3 login. Check the username, password and public key under Edit server, or add them if " +
                    "this server requires a login. iperf3 also rejects logins when the phone's clock is more than about 10 s off.",
                raw,
            )
            "must specify a username, password" in m || "rsa public key" in m || "pem" in m && "key" in m -> RunError(
                "Login details incomplete",
                "iperf3 couldn't use the saved login for $target. Make sure the username, password and the server's public key " +
                    "(a -----BEGIN PUBLIC KEY----- PEM) are all filled in under Edit server.",
                raw,
            )
            "unrecognized option" in m && ("username" in m || "rsa" in m) -> RunError(
                "Login not supported",
                "This iperf3 build can't do server logins. Remove the iperf3 login from the server to test without one.",
                raw,
            )
            "server is busy" in m -> RunError(
                "Server busy",
                "$target is already running a test for someone else. iperf3 servers handle one client at a time — try again in a moment.",
                raw,
            )
            "connection refused" in m -> RunError(
                "Connection refused",
                "Nothing is listening on $target. Check that iperf3 -s is running on the server and that the port is right.",
                raw,
            )
            "timed out" in m || "timeout" in m -> RunError(
                "Server unreachable",
                "No answer from $target within ${CONNECT_TIMEOUT_MS / 1000} s. The server may be off, firewalled, or on a different network/VPN.",
                raw,
            )
            "no route to host" in m || "network is unreachable" in m || "host is unreachable" in m -> RunError(
                "No route to server",
                "Your phone has no network path to $host. Check Wi-Fi / mobile data, and whether the server is only reachable over a VPN.",
                raw,
            )
            "name or service not known" in m || "no address associated" in m || "temporary failure in name resolution" in m ||
                "servname" in m || "not known" in m || "unknown host" in m || "getaddrinfo" in m || "unable to resolve" in m -> RunError(
                "Can't find host",
                "\"$host\" couldn't be resolved. Check the spelling, or use the server's IP address.",
                raw,
            )
            "unknown control message" in m -> RunError(
                "Not an iperf3 server",
                "$target answered, but not like an iperf3 server. Is it running iperf2 or another service on that port?",
                raw,
            )
            "control socket has closed unexpectedly" in m -> RunError(
                "Server closed the connection",
                "$target hung up before the test started. If this server requires an iperf3 login, add it under Edit server. " +
                    "Otherwise it may not be an iperf3 server (iperf2 or another service on that port).",
                raw,
            )
            "connection reset" in m || "broken pipe" in m -> RunError(
                "Connection dropped",
                "The connection to $target was reset during the test. The server may have restarted, or the network changed.",
                raw,
            )
            "unable to connect" in m -> RunError(
                "Can't connect",
                "Couldn't open a connection to $target. Check the address, that iperf3 -s is running there, and that your phone can reach that network.",
                raw,
            )
            else -> RunError("Test failed", raw.replaceFirstChar { it.uppercase() }, raw)
        }
    }
}
