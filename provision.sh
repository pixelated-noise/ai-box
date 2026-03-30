#!/bin/sh
set -e

# Log to serial console
exec > /dev/hvc0 2>&1
echo '=== aibox provisioning started ==='

# Hostname
echo 'aibox' > /etc/hostname
hostname aibox

# Load kernel modules and networking
rc-service modloop start
ip link set eth0 up
udhcpc -i eth0
sleep 2

# Enable online repositories
cat > /etc/apk/repositories << 'REPOEOF'
https://dl-cdn.alpinelinux.org/alpine/v{{alpine-repo}}/main
https://dl-cdn.alpinelinux.org/alpine/v{{alpine-repo}}/community
REPOEOF
apk update

# Create user
apk add bash sudo shadow
useradd -m -d {{home}} -s /bin/bash {{username}} || true
echo '{{username}}:*' | chpasswd -e
chown root:root / /etc
chown root:root $(dirname {{home}})
chown -R {{username}}:{{username}} {{home}}
echo '{{username}} ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/{{username}}

# SSH
apk add openssh
mkdir -p /etc/ssh/authorized_keys
cat > /etc/ssh/authorized_keys/{{username}} << 'SSHEOF'
{{pub-key}}
SSHEOF
cp /etc/ssh/authorized_keys/{{username}} /etc/ssh/authorized_keys/root
chmod 644 /etc/ssh/authorized_keys/*
sed -i 's/^#PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's|^AuthorizedKeysFile.*|AuthorizedKeysFile /etc/ssh/authorized_keys/%u|' /etc/ssh/sshd_config
rc-update add sshd default
/etc/init.d/sshd start || true

# Mount shared directories from host
{% for mount in mounts %}
mkdir -p {{mount.guest}}
mount -t virtiofs{% if mount.readonly %} -o ro{% endif %} mount{{mount.index}} {{mount.guest}}
{% endfor %}

# Add ~/.local/bin to PATH and Claude auth for all sessions
echo 'export PATH="$HOME/.local/bin:$PATH"' >> /etc/profile
echo 'export CLAUDE_CODE_OAUTH_TOKEN="{{oauth-token}}"' >> /etc/profile
{% if cd-home %}
echo 'cd {{home}}' >> /etc/profile
{% endif %}

{% if provision %}
# User provisioning
chmod 666 /dev/hvc0
{% for cmd in provision %}
{{cmd}}
{% endfor %}
{% endif %}

echo '=== aibox provisioning complete ==='
