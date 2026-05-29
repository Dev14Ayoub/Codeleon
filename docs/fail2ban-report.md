# Fail2ban — security report

> Generated from a live operational snapshot of the production server
> (`ubuntu-8gb-hel1-1`, Hetzner CX22, 8 GB) on **2026-05-29 21:13 UTC**.
> Intended as a section input for the PFE final report.

---

## 1. Role in the security architecture

Codeleon's production deployment sits behind three concentric defenses:

1. **UFW** — host firewall, deny-by-default on incoming. Only the WireGuard
   UDP port and the `tailscale0` interface are allowed.
2. **fail2ban** — log-based brute-force protection. Watches `/var/log/auth.log`
   via systemd-journal and bans repeat-offender IPs at the iptables level.
3. **Tailscale auth** — only authenticated tailnet members can reach the
   service IP (`100.106.32.95`); the public IP (`89.167.65.180`) is closed.

fail2ban is the **belt-and-suspenders layer**: if UFW were ever misconfigured
(e.g. a `ufw allow 22` typed by mistake), fail2ban catches the inevitable
wave of bot attempts without operator intervention.

## 2. Active jail

A single jail is active: **sshd**.

```
Status for the jail: sshd
|- Filter
|  |- Currently failed: 0
|  |- Total failed:     174
|  `- Journal matches:  _SYSTEMD_UNIT=ssh.service + _COMM=sshd
`- Actions
   |- Currently banned: 0
   |- Total banned:     20
   `- Banned IP list:
```

Effective configuration at snapshot time:

| Parameter | Value | Meaning |
|-----------|-------|---------|
| `bantime` | 600 s | Banned IP is unblocked after 10 minutes |
| `findtime` | 600 s | Window over which failed attempts are counted |
| `maxretry` | 5 | Number of failures inside `findtime` that trigger a ban |

## 3. Operational data — historical bans

Across the 28 May 2026 attack window, fail2ban banned **20 distinct IP
addresses** stemming from **174 failed SSH attempts**. The most persistent
attacker re-attempted 12 times across multiple ban/unban cycles:

```
Top 10 IPs banned historically (frequency of ban events):
 12  221.8.39.178
  1  85.192.31.14
  1  43.165.68.55
  1  212.51.34.150
  1  185.203.237.43
  1  159.223.59.82
  1  144.31.153.250
  1  106.13.165.101
  1  102.213.34.99
```

## 4. Key insight from the data

**All 174 failed attempts cluster between 2026-05-28 18:54 and 21:47 UTC** —
a 3-hour window matching the period during which SSH on the public IP was
reachable before the UFW lock-down was applied. Since 21:47 UTC on 28/05,
fail2ban has logged **zero additional events**, confirming that:

- the UFW rule chain successfully drops public-IP SSH attempts before sshd
  ever sees them;
- the residual attack surface for SSH is now reduced to the tailnet, where
  authorised members reach the service over WireGuard.

The repeated re-bans of `221.8.39.178` also exposed a tuning issue: with
`bantime=600s`, the attacker simply waited out the 10-minute window and
retried.

## 5. Hardening applied after analysis

Configuration updated in `/etc/fail2ban/jail.d/sshd-hardened.conf`:

```ini
[sshd]
enabled = true
# Initial ban duration: one hour
bantime = 3600
# Detection window: 30 minutes
findtime = 1800
# Three failed attempts trigger the ban (was 5)
maxretry = 3
# Progressive ban: double the duration on each repeat offence,
# capped at 24 hours so the iptables chain doesn't grow forever.
bantime.increment = true
bantime.factor = 2
bantime.maxtime = 86400
```

With these settings, a repeat offender like `221.8.39.178` would have hit
the first ban at 1 h, the second at 2 h, the third at 4 h, the fourth at
8 h, and stayed quiet long enough for the IP to fall out of any active
botnet rotation list.

## 6. Useful operational commands

| Goal | Command |
|------|---------|
| Status of every jail | `sudo fail2ban-client status` |
| Detail of the sshd jail | `sudo fail2ban-client status sshd` |
| Currently banned IPs | `sudo fail2ban-client status sshd \| grep -A 1 "Banned IP"` |
| Recent ban/unban events | `sudo grep -E 'Ban\|Unban' /var/log/fail2ban.log \| tail -20` |
| Ban a specific IP manually | `sudo fail2ban-client set sshd banip 1.2.3.4` |
| Unban a specific IP | `sudo fail2ban-client set sshd unbanip 1.2.3.4` |
| Reload after a config change | `sudo fail2ban-client reload` |
| One-shot security report | (see `scripts/security-report.sh` if added later) |

## 7. Conclusion

For a Tailscale-only deployment with a closed public IP, fail2ban brings
limited *current* value — UFW already drops everything before sshd sees
it. Its real role is **defense in depth**: it covers the failure mode where
the firewall misconfigures (operator error, package update reset, etc.),
and it provides the audit trail that proves the historical attack window
was successfully closed. The 20 historical bans and 3-hour exploit window
are concrete evidence that the layered architecture works as designed.
