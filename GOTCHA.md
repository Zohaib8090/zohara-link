# Gotchas (Zohara Link)

Real traps that cost time. If you hit one again, it means this file was not read. Add to it when something takes more than a few
minutes to find. (More traps for the whole project: `GOTCHA.md` in the `zohara` repo.)

## Daemon

* **A safety check can break a normal case.** The "no more than the size the phone declared" rule deleted every file whose size the phone
  did not know (Android reports 0 for some sources). Test a hardening rule with the awkward honest case, not only the attacker.
  `tests/e2e_files.py` has the unknown-size case now.
* **Anything per-connection must be cleaned up when the connection ends.** Half-finished file transfers piled up in a table and, at the limit
  of 4, the daemon refused every later file ("too many transfers") while the phone streamed into nothing. `drop_transfers_of` fixes it.
* **The phone must hear the computer's answer.** A refused file offer used to be invisible. The phone waits for `FILE_ACCEPT` and shows why.
* **Do not run a tool per mouse move.** Starting `ydotool` for every finger movement and waiting for it made the pointer lag further and
  further behind. Moves are queued and merged (`desktop::merge_ops`); the sender also merges at about 60 per second. Clicks for
  ydotool 1.x are `0xC0` (left) and `0xC1` (right), not `0x110`.
* **`playerctl` is not on every system.** The music buttons did nothing without it. Fall back to MPRIS over D-Bus with `busctl` (ships with
  systemd). The daemon pushes now-playing to the phone every 2 s when it changes.
* **Announce only real networks over mDNS.** Docker/VM interfaces and IPv6 link-local addresses in the announcement made phones pick
  an address they cannot reach (`mdns::is_virtual_iface`). The phone side must also use an IPv4 address only.
* **Some Wi-Fi blocks mDNS, or all phone-to-computer traffic ("client isolation").** The phone can scan its own subnet for the port as a
  fallback (`NsdDiscoveryManager.scanLocalNetwork`), and Settings shows the computer's address to type. Isolation blocks all of it.
* **Pairing is closed on purpose.** "Pairing is off on the computer" is not a bug: press Pair a phone in Settings first.
* **`makepkg` in CI needs `--nodeps`** now that the package lists runtime dependencies (`playerctl`, `ydotool`, ...): the build container
  does not have them and does not need them.
* **A new package source repo must be on `zohara-packages`' allow list** (`scripts/validate-publish-inputs.sh`, `publish.yml`).
* **Dependencies listed in `depends` reach installed machines only through an update of this package.** That is why the daemon
  depends on `playerctl`, `wl-clipboard`, `ydotool`, `libnotify`.

* **Unasked packets break strict test readers.** After pairing or AUTH the daemon now also sends `ADDRESSES`. A test that reads
  "the next line" must skip it (`rd()` in `tests/e2e.py` does). The phone ignores types it does not know.
* **Screen capture on Wayland needs Spectacle (Plasma) or grim; X11 needs maim/import.** None of them ships in the package
  `depends` yet; without one the phone gets `SCREEN_STOPPED` with the reason. Absolute pointer moves (`ydotool mousemove --absolute`)
  and wheel scrolling are written from the tools' documentation and not yet seen working on a real screen.

## Tests

* **A Python `ssl` socket `.close()` does not end the connection while a `makefile()` of it is alive.** To simulate a phone dropping off,
  call `shutdown(SHUT_RDWR)` first, or the daemon never sees the disconnect.
* **No D-Bus bindings for Python in the cloud container.** A fake media player for tests is a few lines of Rust with `zbus`
  (see `tests/e2e_media.py` header).
* **`pkill -f NAME` inside a command that contains NAME kills its own shell** (exit 144). Use `pkill -x`.

## Phone app

* **There is no Android compiler in a cloud session.** Push and read the GitHub Actions log. A one-word mistake (a parameter named `vm`
  called as `viewModel =`) cost a whole cycle: re-read call sites after renaming.
* **Do not paste a whole Gradle log.** The stack trace is hundreds of lines; the useful lines start with `e: file:///`. Use a small `tail_lines`.
* **Ongoing notifications re-post every few seconds** (a health tracker's step counter, music, downloads). Mirroring each re-post showed
  the same pop-up again and again. Skip `FLAG_ONGOING_EVENT`, foreground-service, progress, media and group-summary notifications, skip
  identical re-posts, and let the person switch an app off (Access > Choose which apps). The daemon also drops identical ones.
* **Android resolves one mDNS service at a time**; a second resolve fails with `FAILURE_ALREADY_ACTIVE` (3). Retry after a moment.
* **Send packets from one ordered writer.** A coroutine per packet on a thread pool reordered them (a click could overtake the moves).
* **The app listed Firebase, Retrofit, OkHttp and Moshi and used none of them.** They were still packaged and Firebase starts itself at launch.
  They are removed. Check `dependencies` before adding one.
* **An update only installs over the old app if both are signed with the same key.** Debug builds use a throwaway key made fresh in CI,
  so each is a different app to Android ("package conflicts"). The permanent key lives in GitHub secrets (see `docs/ANDROID.md`); the first
  switch to it needs one uninstall. **`versionCode` must grow with every build** (CI uses run number + 100); a local build is 1.
* **The signing key and its passwords must never be committed or printed.** Lose the key and every phone must reinstall.
* **Apps cannot install themselves silently.** The update hands the APK to Android's installer; the person confirms, and must allow
  "install unknown apps" for Zohara Link once.

## Process

* **`main` moves while you work** (the owner edits too). `git push` can be refused as non-fast-forward: fetch, merge, push again.
* **Scheduled self-checks are the way to wait for CI**; do not poll in a loop and do not `sleep` chains.
