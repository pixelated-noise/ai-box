# ai-box

A sandboxed environment for running [Claude Code](https://docs.anthropic.com/en/docs/claude-code) in fully autonomous ("YOLO") mode. It boots an ephemeral Alpine Linux VM on macOS using [vfkit](https://github.com/crc-org/vfkit), provisions it automatically, and provides SSH access -- giving Claude Code a disposable Linux environment where it can run any command without risk to your host system.

## Why

Claude Code's autonomous mode is powerful but risky on a host machine -- it can modify files, install packages, and run arbitrary commands without confirmation. ai-box solves this by giving it an isolated VM that:

- **Resets on every boot** -- the Alpine live ISO runs from RAM, nothing persists
- **Can't affect the host** -- NAT networking, configurable read-only mounts
- **Network lockdown** -- optionally restrict outbound traffic to Anthropic API only
- **Provisions itself** -- SSH, packages, tools, and auth are set up automatically

## Prerequisites

- **macOS 13+** (Apple Silicon)
- [Babashka](https://github.com/babashka/babashka#installation) (`brew install borkdude/brew/babashka`)
- [vfkit](https://github.com/crc-org/vfkit) (`brew install vfkit`)
- [Claude Code](https://docs.anthropic.com/en/docs/claude-code) installed on the host (for `setup-token`)

## Usage

```sh
# Boot the VM (downloads ISO on first run, provisions automatically)
# On first run, this will also run `claude setup-token` to generate
# a long-lived OAuth token for the VM (requires Claude subscription)
bb boot

# In another terminal, SSH into the VM once provisioning completes
bb ssh

# Lock down network to Anthropic API only (recommended for YOLO mode)
bb block

# Run Claude Code inside the VM
claude --dangerously-skip-permissions

# When done, restore full network access
bb allow
```

Wait for `=== aibox provisioning complete ===` in the boot terminal before running `bb ssh`. Run `bb block` after provisioning completes (provisioning needs unrestricted internet to install packages).

### All tasks

| Task | Description |
|------|-------------|
| `bb boot` | Download ISO (if needed), authenticate (if needed), build overlay, boot VM |
| `bb ssh` | SSH into the running VM |
| `bb block` | Restrict VM network to Anthropic API only (requires sudo) |
| `bb allow` | Remove VM network restrictions (requires sudo) |
| `bb login` | Generate/refresh the OAuth token without booting |
| `bb download` | Download the Alpine ISO only |
| `bb clean` | Remove generated files (keeps the ISO) |

## Network restriction

`bb block` uses macOS's `pf` (packet filter) to restrict the VM's outbound traffic to only:

- **Anthropic API** (160.79.104.0/23)
- **DNS** (port 53, for resolving api.anthropic.com)
- **Local subnet** (for SSH access from the host)

All IPv6 traffic is blocked to prevent bypass via dual-stack sites. The rules are applied on the VM's bridge interface (`bridge100`) using a pf anchor (`com.apple/aibox`), so the main firewall configuration is never modified.

This restriction is enforced on the host, so even if Claude Code runs with full permissions inside the VM, it cannot remove or modify the firewall rules.

`bb allow` removes all restrictions, restoring full internet access.

## Authentication

ai-box uses Claude Code's `setup-token` command to generate a long-lived OAuth token (valid for 1 year) that gets injected into the VM as `CLAUDE_CODE_OAUTH_TOKEN`. This uses your existing Claude subscription -- no separate API billing.

On first `bb boot` (or `bb login`), you'll be prompted to authenticate via your browser. The token is cached in `data/oauth-token` and reused on subsequent boots. Run `bb clean` to force re-authentication.

The host's `~/.claude` directory is mounted read-only inside the VM for configuration, and `~/.claude.json` is copied into the overlay.

## Configuration

All settings are in `config.yaml`:

```yaml
alpine-version: "3.23.3"
headless: false

vm:
  cpus: 4
  memory: 4096
  mac: "52:54:00:ab:cd:01"

mounts:
  - host: ~/.claude
    guest: /root/.claude
    readonly: true
  - host: ~/projects
    guest: /root/projects
    readonly: false

provision:
  - apk update
  - apk add curl bash
  - apk add libgcc libstdc++ ripgrep
  - apk add nodejs npm
  - npm install -g @anthropic-ai/claude-code
```

### Options

- **`headless`** -- set to `true` to skip the GUI window (serial console only)
- **`provision`** -- shell commands that run after networking and SSH are configured
- **`vm`** -- CPU count, memory (MiB), and MAC address for deterministic IP assignment
- **`mounts`** -- host directories to share with the VM via virtio-fs:
  - `host` -- path on the host (`~` is expanded to home directory)
  - `guest` -- mount point inside the VM
  - `readonly` -- set to `true` to mount read-only (default: `false`)

## Risks

- **No persistence** -- the VM is ephemeral. Any work done inside it is lost when vfkit stops, unless it's on a read-write mount. Clone repos into a mounted directory and push results before shutting down.
- **Read-write mounts** -- directories mounted with `readonly: false` can be modified by processes in the VM. Use read-only mounts for anything you don't want changed.
- **Network access** -- the VM has full outbound internet access by default. Use `bb block` to restrict it to Anthropic API only before running Claude Code in autonomous mode.
- **OAuth token** -- the long-lived OAuth token is stored in `data/oauth-token` and injected into the VM. Keep this file secure. Run `bb clean` to remove it.
- **Not a security boundary** -- vfkit uses Apple's Virtualization.framework, which provides reasonable isolation but is not designed as a security sandbox. Don't rely on this for running untrusted adversarial code.
- **Provisioning runs every boot** -- since nothing persists, packages are re-downloaded each time. Boot-to-ready takes a minute or two depending on your connection.

## Architecture

```
bb boot
  |
  |-- Downloads Alpine Linux aarch64 ISO (cached in data/)
  |-- Generates SSH key pair (cached in data/)
  |-- Obtains OAuth token via `claude setup-token` (cached in data/)
  |-- Builds an Alpine overlay (apkovl):
  |     |-- Starts modloop (kernel modules)
  |     |-- Configures networking (DHCP)
  |     |-- Enables online APK repositories
  |     |-- Installs openssh, injects SSH public key
  |     |-- Mounts shared directories from host (virtio-fs)
  |     |-- Sets CLAUDE_CODE_OAUTH_TOKEN for Claude auth
  |     |-- Runs user provision commands from config.yaml
  |     `-- Logs progress to serial console (/dev/hvc0)
  |-- Packages overlay as FAT disk image (via hdiutil)
  `-- Launches vfkit with:
        |-- Alpine ISO as USB mass storage (boot device)
        |-- Overlay image as USB mass storage (auto-applied by Alpine init)
        |-- virtio-net with fixed MAC (for IP lookup via DHCP leases)
        |-- virtio-serial on stdio (provisioning log output)
        |-- virtio-fs shares for each configured mount
        `-- virtio-gpu + input devices (unless headless)

bb block
  |-- Detects VM bridge interface (e.g. bridge100)
  `-- Loads pf rules into com.apple/aibox anchor:
        |-- Block all IPv6
        |-- Allow Anthropic API (160.79.104.0/23)
        |-- Allow DNS (port 53)
        |-- Allow local subnet (SSH)
        `-- Block everything else

bb ssh
  |-- Reads /var/db/dhcpd_leases, matches on configured MAC address
  `-- Connects via SSH using the generated key pair
```

### Key technologies

- **[vfkit](https://github.com/crc-org/vfkit)** -- lightweight macOS VM runner built on Apple's Virtualization.framework
- **[Alpine Linux](https://alpinelinux.org/)** -- minimal Linux distribution (~370MB ISO), boots to a live system from RAM
- **[apkovl](https://wiki.alpinelinux.org/wiki/Alpine_local_backup)** -- Alpine's overlay mechanism, used to inject provisioning at boot without modifying the ISO
- **[Babashka](https://github.com/babashka/babashka)** -- fast Clojure scripting runtime, used for all orchestration
- **[pf](https://man.openbsd.org/pf.conf)** -- macOS packet filter, used for host-side network restriction via anchors
