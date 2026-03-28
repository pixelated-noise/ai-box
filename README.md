# ai-box

A sandboxed environment for running [Claude Code](https://docs.anthropic.com/en/docs/claude-code) in fully autonomous ("YOLO") mode. It boots an ephemeral Alpine Linux VM on macOS using [vfkit](https://github.com/crc-org/vfkit), provisions it automatically, and provides SSH access -- giving Claude Code a disposable Linux environment where it can run any command without risk to your host system.

## Why

Claude Code's autonomous mode is powerful but risky on a host machine -- it can modify files, install packages, and run arbitrary commands without confirmation. ai-box solves this by giving it an isolated VM that:

- **Resets on every boot** -- the Alpine live ISO runs from RAM, nothing persists
- **Can't affect the host** -- NAT networking, no shared filesystems
- **Provisions itself** -- SSH, packages, and tools are set up automatically

## Prerequisites

- **macOS 13+** (Apple Silicon)
- [Babashka](https://github.com/babashka/babashka#installation) (`brew install borkdude/brew/babashka`)
- [vfkit](https://github.com/crc-org/vfkit) (`brew install vfkit`)

## Usage

```sh
# Boot the VM (downloads ISO on first run, provisions automatically)
bb boot

# In another terminal, SSH into the VM once provisioning completes
bb ssh

# Run Claude Code inside the VM
claude --dangerously-skip-permissions
```

Wait for `=== aibox provisioning complete ===` in the boot terminal before running `bb ssh`.

### All tasks

| Task | Description |
|------|-------------|
| `bb boot` | Download ISO (if needed), build overlay, boot VM |
| `bb ssh` | SSH into the running VM |
| `bb download` | Download the Alpine ISO only |
| `bb clean` | Remove generated files (keeps the ISO) |

## Configuration

All settings are in `config.yaml`:

```yaml
alpine-version: "3.23.3"
headless: false

vm:
  cpus: 4
  memory: 4096
  mac: "52:54:00:ab:cd:01"

provision:
  - apk update
  - apk add curl bash
  - apk add libgcc libstdc++ ripgrep
  - curl -fsSL https://claude.ai/install.sh | bash
```

- **`headless`** -- set to `true` to skip the GUI window (serial console only)
- **`provision`** -- shell commands that run after networking and SSH are configured
- **`vm`** -- CPU count, memory (MiB), and MAC address for deterministic IP assignment

## Risks

- **No persistence** -- the VM is ephemeral. Any work done inside it is lost when vfkit stops. Clone repos and push results before shutting down.
- **Network access** -- the VM has outbound internet access via NAT. A compromised process could make network requests, though it cannot reach the host's local services.
- **Not a security boundary** -- vfkit uses Apple's Virtualization.framework, which provides reasonable isolation but is not designed as a security sandbox. Don't rely on this for running untrusted adversarial code.
- **Provisioning runs every boot** -- since nothing persists, packages are re-downloaded each time. Boot-to-ready takes a minute or two depending on your connection.

## Architecture

```
bb boot
  |
  |-- Downloads Alpine Linux aarch64 ISO (cached in data/)
  |-- Generates SSH key pair (cached in data/)
  |-- Builds an Alpine overlay (apkovl):
  |     |-- Starts modloop (kernel modules)
  |     |-- Configures networking (DHCP)
  |     |-- Enables online APK repositories
  |     |-- Installs openssh, injects SSH public key
  |     |-- Runs user provision commands from config.yaml
  |     `-- Logs progress to serial console (/dev/hvc0)
  |-- Packages overlay as FAT disk image (via hdiutil)
  `-- Launches vfkit with:
        |-- Alpine ISO as USB mass storage (boot device)
        |-- Overlay image as USB mass storage (auto-applied by Alpine init)
        |-- virtio-net with fixed MAC (for IP lookup via DHCP leases)
        |-- virtio-serial on stdio (provisioning log output)
        `-- virtio-gpu + input devices (unless headless)

bb ssh
  |-- Reads /var/db/dhcpd_leases, matches on configured MAC address
  `-- Connects via SSH using the generated key pair
```

### Key technologies

- **[vfkit](https://github.com/crc-org/vfkit)** -- lightweight macOS VM runner built on Apple's Virtualization.framework
- **[Alpine Linux](https://alpinelinux.org/)** -- minimal Linux distribution (~370MB ISO), boots to a live system from RAM
- **[apkovl](https://wiki.alpinelinux.org/wiki/Alpine_local_backup)** -- Alpine's overlay mechanism, used to inject provisioning at boot without modifying the ISO
- **[Babashka](https://github.com/babashka/babashka)** -- fast Clojure scripting runtime, used for all orchestration
