# Hosting: phone + desktop access with Tailscale

The app runs on a computer you own, and **Tailscale** (free personal plan) gives that computer a private HTTPS address that only your own devices can reach. Nothing is exposed to the public internet.

- **Now:** run it on your laptop. Your phone can use it whenever the laptop is on and awake.
- **Long term:** move it to an always-on home server (see the last section). Same setup, no laptop needed.

The password login (SPEC §3, Phase 6) stays on as a second lock, in case a device signed into your Tailscale account is ever lost.

---

## One-time setup (about 10 minutes)

### 1. Create a Tailscale account
Go to tailscale.com and sign up with Google, Apple, Microsoft or GitHub. Pick the free **Personal** plan. This account is your private network ("tailnet"); every device you sign in joins it.

### 2. Install Tailscale on the computer that runs the app
- **Mac:** install from the Mac App Store, or the standalone download on tailscale.com.
- **Windows:** download the installer from tailscale.com.
- **Linux / Raspberry Pi:** `curl -fsSL https://tailscale.com/install.sh | sh`, then `sudo tailscale up`.

Sign in with the account from step 1.

### 3. Install Tailscale on your phone
App Store (iPhone) or Play Store (Android). Sign in with the **same** account. Leave the VPN toggle on. It only routes traffic to your own devices, so normal browsing isn't slowed.

### 4. Turn on HTTPS for your tailnet
In the Tailscale admin console (login.tailscale.com), open **DNS**:
- make sure **MagicDNS** is on (it is by default on new accounts);
- turn on **HTTPS Certificates**.

If you skip this, step 6 prints a link to turn it on.

### 5. Start the app
From the repo folder on that computer:
```sh
docker compose up -d --build
```
Check it locally: http://localhost:8080 should load.

### 6. Share it on your tailnet (private, not public)
```sh
tailscale serve --bg 8080
```
It prints your app's address, something like `https://my-laptop.tail1234.ts.net`. `--bg` keeps it running in the background, including after a reboot.

On Mac or Windows, the `tailscale` command comes with the app. If your terminal can't find it, see "Using the CLI" in Tailscale's docs for your OS.

Useful commands:
- `tailscale serve status`: show what's being shared
- `tailscale serve reset`: stop sharing

> ⚠️ Use `tailscale serve`, **never `tailscale funnel`**. Funnel publishes the app to the whole internet.

### 7. Open it on your phone
Open the address from step 6 in Safari or Chrome, then install it so it opens full screen like a regular app (no browser bar):
- **iPhone:** in Safari, Share button → **Add to Home Screen**
- **Android:** in Chrome, ⋮ menu → **Install app** (or **Add to Home screen**)

Tip: link new banks from a regular browser tab rather than the home-screen app. Banks that sign you in on their own website can lose their way back to a home-screen app on iPhone.

### 8. Keep the laptop awake (laptop setups only)
Scheduled syncs and phone access only work while the computer is awake.
- **Mac:** System Settings → Battery (or Energy) → turn on "Prevent automatic sleeping when the display is off" for when it's plugged in.
- **Windows:** Settings → System → Power → set "When plugged in, put my device to sleep after" to **Never**.

If the laptop sleeps, nothing is lost. The next sync picks up every transaction since the last one.

---

## What changes in Phase 6 because of this
- **Plaid OAuth redirect URI:** some banks sign you in on their own site and send you back to the app. Plaid needs that return address registered in its dashboard. It will be `https://<your-machine>.<tailnet>.ts.net/oauth-return` (exact path set in Phase 6).
- **Webhooks:** not possible, because the app has no public address. That's fine: the 6-hour scheduled sync and the "Sync now" button cover it (SPEC §5 lists webhooks as optional).
- **Secure cookie:** Tailscale tells the app that requests arrived over HTTPS (`X-Forwarded-Proto: https`). Phase 6 makes nginx pass that through and Spring trust it, so the login cookie can be marked Secure.

---

## Long term: an always-on home server

Yes, a home server works, and it's the best long-term setup. The app runs there 24/7: syncs happen on schedule, and your phone works even when your laptop is closed. Everything in this repo already runs on it unchanged. All the Docker images (Postgres, Java, Node, nginx) support both regular PCs and ARM boards like the Raspberry Pi.

### What hardware
Any of these works. Pick by what you have or want to spend.

| Option | Rough cost | Notes |
|---|---|---|
| Old laptop or desktop you already own | Free | Plenty of power. Uses more electricity; a laptop's battery acts as a built-in backup power supply. |
| Mini PC (e.g. Intel N100-class) | ~$120–200 | Quiet, low power (~6–10 W), x86, very easy. A strong pick. |
| Raspberry Pi 5, 8 GB | ~$120–160 with case, power supply and SSD | Lowest power (~5 W). **Boot from an SSD, not an SD card:** a database wears out SD cards. |
| Mac mini | Higher | Works great if you have a spare one. |

Minimum: 64-bit CPU, **4 GB RAM** (8 GB comfortable), an **SSD**, and a **wired ethernet** connection if possible.

### Setting it up
1. Install a 64-bit OS: Raspberry Pi OS (64-bit) or Ubuntu Server 24.04 LTS. Mini PCs and old PCs can use Ubuntu Server.
2. Install Docker Engine and the Compose plugin.
3. Install Tailscale and sign in (step 2 above, Linux instructions).
4. Clone the repo, copy your `.env` over (see "Moving from laptop to server" below), then run steps 5–6 above.
5. Turn on automatic security updates (`unattended-upgrades` on Ubuntu / Pi OS).
6. In your router, give the server a fixed local IP (a "DHCP reservation"). This is optional with Tailscale, but handy for local SSH.

The containers already restart on their own after a reboot or crash (`restart: unless-stopped` in `docker-compose.yml`), provided Docker is set to start at boot. It is by default on Linux.

### Backups (important once real data is in)
- **Database:** a nightly `pg_dump`, compressed and copied off the server (another computer, an external drive, or encrypted cloud storage). Phase 6 adds a `make backup` command for this. It contains your financial history, so keep the copy encrypted.
- **`TOKEN_ENC_KEY`:** keep a copy in your password manager. Without it, the stored bank logins can't be decrypted and you would have to re-link banks, which uses Plaid slots you can't get back.

### Moving from laptop to server later
No re-linking needed, as long as you move the data and the same key:
1. On the laptop: `docker compose exec db pg_dump -U finance finance > finance.sql`
2. Copy `finance.sql` and `.env` (same `TOKEN_ENC_KEY`!) to the server.
3. On the server: `docker compose up -d db`, restore with `docker compose exec -T db psql -U finance finance < finance.sql`, then `docker compose up -d --build` and `tailscale serve --bg 8080`.
4. **Keep the same web address:** in the Tailscale admin console, remove the old laptop entry (or rename it), then rename the server's machine name to what the laptop was called. The `https://….ts.net` address, your phone's home-screen icon and Plaid's registered redirect URI then all keep working. If the address does change, update the redirect URI in the Plaid dashboard.
5. Stop the app on the laptop so two copies aren't both syncing.
