(ns aibox.core
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(defn- expand-home [path]
  (if (str/starts-with? path "~")
    (str/replace-first path "~" (System/getProperty "user.home"))
    path))

(def config
  (let [user-config    (yaml/parse-string (slurp "config.yaml"))
        alpine-version (:alpine-version user-config)
        iso-filename   (str "alpine-standard-" alpine-version "-aarch64.iso")
        data-dir       "data"
        vm             (:vm user-config)]
    {:alpine-version alpine-version
     :iso-filename   iso-filename
     :iso-url        (str "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/" iso-filename)
     :data-dir       data-dir
     :iso-path       (str data-dir "/" iso-filename)
     :ssh-key-path   (str data-dir "/id_ed25519")
     :efi-vars-path  (str data-dir "/efi-vars")
     :headless       (:headless user-config false)
     :provision      (:provision user-config [])
     :mounts         (mapv (fn [m]
                            (let [host-abs (expand-home (:host m))]
                              (merge m {:host-abs host-abs
                                        :guest    (:guest m host-abs)})))
                          (:mounts user-config []))
     :mac            (:mac vm "52:54:00:ab:cd:01")
     :cpus           (:cpus vm 4)
     :memory         (:memory vm 4096)}))

(defn download []
  (let [{:keys [data-dir iso-path iso-url]} config]
    (fs/create-dirs data-dir)
    (if (fs/exists? iso-path)
      (println "ISO already downloaded:" iso-path)
      (do
        (println "Downloading" iso-url "...")
        (p/shell "curl" "-L" "-o" iso-path "--progress-bar" iso-url)
        (println "Downloaded to" iso-path)))))

(defn- generate-ssh-key []
  (let [{:keys [data-dir ssh-key-path]} config]
    (when-not (fs/exists? ssh-key-path)
      (fs/create-dirs data-dir)
      (println "Generating SSH key pair...")
      (p/shell "ssh-keygen" "-t" "ed25519" "-f" ssh-key-path "-N" "" "-q"))
    ssh-key-path))

(def token-path (str (:data-dir config) "/oauth-token"))

(defn- ensure-oauth-token []
  (if (fs/exists? token-path)
    (str/trim (slurp token-path))
    (do
      (println "No OAuth token found. Running 'claude setup-token'...")
      (let [output (:out (p/shell {:out :string}
                                  "script" "-q" "/dev/null"
                                  "claude" "setup-token"))
            token  (->> (str/split-lines output)
                        (keep #(re-find #"sk-ant-\S+" %))
                        first)]
        (if token
          (do
            (fs/create-dirs (:data-dir config))
            (spit token-path token)
            (println "OAuth token saved.")
            token)
          (do
            (println "Failed to capture token from output:")
            (println "---")
            (println output)
            (println "---")
            (println "Run 'claude setup-token' manually and save the token to" token-path)
            (System/exit 1)))))))

(defn- create-overlay []
  (let [{:keys [data-dir provision]} config
        work-dir    (str data-dir "/.overlay-work")
        apkovl-root (str work-dir "/apkovl")
        tar-dir     (str work-dir "/tar")
        dmg-base    (str (fs/absolutize work-dir) "/overlay")
        dmg-path    (str dmg-base ".dmg")
        img-path    (str (fs/absolutize data-dir) "/overlay.img")
        vol-name    "APKOVL"
        key-path    (generate-ssh-key)
        pub-key     (str/trim (slurp (str key-path ".pub")))
        oauth-token (ensure-oauth-token)
        script      (str "#!/bin/sh\n"
                         "set -e\n\n"
                         "# Log to serial console\n"
                         "exec > /dev/hvc0 2>&1\n"
                         "echo '=== aibox provisioning started ==='\n\n"
                         "# Hostname\n"
                         "echo 'aibox' > /etc/hostname\n"
                         "hostname aibox\n\n"
                         "# Load kernel modules and networking\n"
                         "rc-service modloop start\n"
                         "ip link set eth0 up\n"
                         "udhcpc -i eth0\n"
                         "sleep 2\n\n"
                         "# Enable online repositories\n"
                         "cat > /etc/apk/repositories << 'REPOEOF'\n"
                         "https://dl-cdn.alpinelinux.org/alpine/v"
                         (str/join "." (take 2 (str/split (:alpine-version config) #"\.")))
                         "/main\n"
                         "https://dl-cdn.alpinelinux.org/alpine/v"
                         (str/join "." (take 2 (str/split (:alpine-version config) #"\.")))
                         "/community\n"
                         "REPOEOF\n"
                         "apk update\n\n"
                         "# SSH\n"
                         "echo 'root:aibox' | chpasswd\n"
                         "apk add openssh\n"
                         "mkdir -p /root/.ssh\n"
                         "chmod 700 /root/.ssh\n"
                         "cat > /root/.ssh/authorized_keys << 'SSHEOF'\n"
                         pub-key "\n"
                         "SSHEOF\n"
                         "chmod 600 /root/.ssh/authorized_keys\n"
                         "sed -i 's/^#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config\n"
                         "rc-update add sshd default\n"
                         "/etc/init.d/sshd start\n\n"
                         "# Mount shared directories from host\n"
                         (->> (:mounts config)
                              (map-indexed
                               (fn [i m]
                                 (str "mkdir -p " (:guest m) "\n"
                                      "mount -t virtiofs"
                                      (when (:readonly m) " -o ro")
                                      " mount" i " " (:guest m) "\n")))
                              (str/join))
                         "# Add ~/.local/bin to PATH and Claude auth for all sessions\n"
                         "echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> /etc/profile\n"
                         "echo 'export CLAUDE_CODE_OAUTH_TOKEN=\"" oauth-token "\"' >> /etc/profile\n"
                         (let [home (System/getProperty "user.home")]
                           (when (some #(str/starts-with? (:guest %) home) (:mounts config))
                             (str "echo 'cd " home "' >> /etc/profile\n")))
                         (when (seq provision)
                           (str "\n# User provisioning\n"
                                (str/join "\n" provision) "\n"))
                         "\necho '=== aibox provisioning complete ==='\n")]

    ;; Clean previous work
    (fs/delete-tree work-dir)
    (when (fs/exists? img-path) (fs/delete img-path))

    ;; Create apkovl structure
    (fs/create-dirs (str apkovl-root "/etc/local.d"))
    (fs/create-dirs (str apkovl-root "/etc/runlevels/default"))
    (p/shell "ln" "-sf" "/etc/init.d/local"
             (str apkovl-root "/etc/runlevels/default/local"))

    ;; Write provision script
    (spit (str apkovl-root "/etc/local.d/provision.start") script)
    (p/shell "chmod" "+x" (str apkovl-root "/etc/local.d/provision.start"))

    ;; Include .claude.json in overlay
    (let [claude-json (str (System/getProperty "user.home") "/.claude.json")]
      (when (fs/exists? claude-json)
        (fs/create-dirs (str apkovl-root "/root"))
        (fs/copy claude-json (str apkovl-root "/root/.claude.json"))))

    ;; Create apkovl tar.gz
    (fs/create-dirs tar-dir)
    (let [tar-path (str (fs/absolutize tar-dir) "/aibox.apkovl.tar.gz")]
      (p/shell {:dir apkovl-root} "tar" "czf" tar-path ".")

      ;; Create FAT disk image via hdiutil
      (p/shell "hdiutil" "create" "-size" "2m" "-fs" "MS-DOS"
               "-volname" vol-name "-o" dmg-base)
      (p/shell "hdiutil" "attach" dmg-path)
      (p/shell "cp" tar-path (str "/Volumes/" vol-name "/"))
      (Thread/sleep 1000)
      (p/shell "hdiutil" "detach" (str "/Volumes/" vol-name) "-force")

      ;; Convert DMG to raw disk image
      (let [output (:out (p/shell {:out :string}
                                  "hdiutil" "attach" "-nomount" dmg-path))
            dev    (->> output str/split-lines first str/trim
                        (re-find #"/dev/disk\d+"))]
        (p/shell "dd" (str "if=" dev) (str "of=" img-path) "bs=512")
        (p/shell "hdiutil" "detach" dev)))

    ;; Cleanup
    (fs/delete-tree work-dir)
    (println "Created overlay:" img-path)
    img-path))

(defn boot []
  (let [{:keys [cpus memory efi-vars-path iso-path mac headless]} config
        create-efi?  (not (fs/exists? efi-vars-path))
        overlay-path (create-overlay)
        mount-args   (->> (:mounts config)
                          (map-indexed
                           (fn [i m]
                             ["--device" (str "virtio-fs,sharedDir=" (:host-abs m)
                                              ",mountTag=mount" i)]))
                          (apply concat)
                          vec)
        base-args    (into ["vfkit"
                            "--cpus" (str cpus)
                            "--memory" (str memory)
                            "--bootloader" (str "efi,variable-store=" efi-vars-path
                                                (when create-efi? ",create"))
                            "--device" (str "usb-mass-storage,path=" iso-path ",readonly")
                            "--device" (str "usb-mass-storage,path=" overlay-path ",readonly")
                            "--device" (str "virtio-net,nat,mac=" mac)
                            "--device" "virtio-serial,stdio"]
                           mount-args)
        gui-args     ["--device" "virtio-input,keyboard"
                      "--device" "virtio-input,pointing"
                      "--device" "virtio-gpu,width=1024,height=768"
                      "--gui"]
        args         (if headless base-args (into base-args gui-args))]
    (println "Booting Alpine Linux" (if headless "(headless)" "(GUI)") "...")
    (apply p/shell args)))

(defn- parse-dhcp-leases []
  (let [content (slurp "/var/db/dhcpd_leases")]
    (->> (str/split content #"\}\n")
         (map (fn [block]
                (->> (str/split block #"\n")
                     (keep (fn [line]
                             (when-let [[_ k v] (re-matches #"\s*(\S+)=(\S+).*" line)]
                               [(keyword k) v])))
                     (into {}))))
         (filter :ip_address))))

(defn- vm-ip []
  (let [mac        (:mac config)
        hw-mac     (str "1," mac)
        leases     (parse-dhcp-leases)
        by-mac     (->> leases (filter #(= hw-mac (:hw_address %))) first)
        by-recency (->> leases
                        (sort-by #(Long/parseLong (subs (:lease % "0x0") 2) 16))
                        last)]
    (:ip_address (or by-mac by-recency))))

(defn ssh []
  (let [{:keys [ssh-key-path]} config]
    (if-let [ip (vm-ip)]
      (do
        (let [cmd ["ssh"
                   "-i" ssh-key-path
                   "-o" "StrictHostKeyChecking=no"
                   "-o" "UserKnownHostsFile=/dev/null"
                   (str "root@" ip)]]
          (println (str/join " " cmd))
          (apply p/shell cmd)))
      (do
        (println "No VM found in DHCP leases. Is the VM running?")
        (System/exit 1)))))

(defn login []
  (ensure-oauth-token)
  (println "Token stored at" token-path))

(defn- find-bridge-interface []
  (let [ip      (vm-ip)
        gateway (when ip (str/replace ip #"\.\d+$" ".1"))
        output  (when gateway
                  (:out (p/shell {:out :string} "ifconfig")))]
    (when output
      (->> (str/split output #"(?m)^(?=\S)")
           (keep (fn [block]
                   (when (and (str/includes? block gateway)
                              (re-find #"^bridge\d+" block))
                     (re-find #"^bridge\d+" block))))
           first))))

(defn restrict-network []
  (if-let [ip (vm-ip)]
    (if-let [bridge (find-bridge-interface)]
      (let [subnet (str/replace ip #"\.\d+$" ".0/24")
            rules  (str "block quick on " bridge " inet6 all\n"
                        "pass quick on " bridge " from " ip " to 160.79.104.0/23\n"
                        "pass quick on " bridge " proto { tcp udp } from " ip " to any port 53\n"
                        "pass quick on " bridge " from " ip " to " subnet "\n"
                        "block quick on " bridge " from " ip " to any\n")]
        (spit "/tmp/aibox-pf.rules" rules)
        (p/shell "sudo" "pfctl" "-a" "com.apple/aibox" "-f" "/tmp/aibox-pf.rules")
        (p/shell {:continue true} "sudo" "pfctl" "-e")
        (println "Network restricted to Anthropic API only on" bridge "for" ip))
      (do
        (println "Could not find bridge interface for VM.")
        (System/exit 1)))
    (do
      (println "No VM found in DHCP leases. Is the VM running?")
      (System/exit 1))))

(defn open-network []
  (p/shell "sudo" "pfctl" "-a" "com.apple/aibox" "-F" "rules")
  (println "Network restrictions removed."))

(defn clean [{:keys [logout]}]
  (let [{:keys [data-dir iso-path]} config
        keep? #{iso-path (when-not logout token-path)}]
    (doseq [f (fs/list-dir data-dir)]
      (when-not (keep? (str f))
        (fs/delete-tree f)))
    (println "Done.")))
