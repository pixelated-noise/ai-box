(ns aibox.core
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def config
  (let [alpine-version "3.23.3"
        iso-filename   (str "alpine-standard-" alpine-version "-aarch64.iso")
        data-dir       "data"]
    {:alpine-version alpine-version
     :iso-filename   iso-filename
     :iso-url        (str "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/" iso-filename)
     :data-dir       data-dir
     :iso-path       (str data-dir "/" iso-filename)
     :ssh-key-path   (str data-dir "/id_ed25519")
     :efi-vars-path  (str data-dir "/efi-vars")
     :mac            "52:54:00:ab:cd:01"
     :cpus           4
     :memory         4096}))

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

(defn- create-overlay []
  (let [{:keys [data-dir]} config
        work-dir    (str data-dir "/.overlay-work")
        apkovl-root (str work-dir "/apkovl")
        tar-dir     (str work-dir "/tar")
        dmg-base    (str (fs/absolutize work-dir) "/overlay")
        dmg-path    (str dmg-base ".dmg")
        img-path    (str (fs/absolutize data-dir) "/overlay.img")
        vol-name    "APKOVL"
        key-path    (generate-ssh-key)
        pub-key     (str/trim (slurp (str key-path ".pub")))
        user-script (when (fs/exists? "provision/setup.sh")
                      (slurp "provision/setup.sh"))
        script      (str "#!/bin/sh\n"
                         "set -e\n\n"
                         "# Load kernel modules and networking\n"
                         "rc-service modloop start\n"
                         "ip link set eth0 up\n"
                         "udhcpc -i eth0\n"
                         "sleep 2\n\n"
                         "# SSH\n"
                         "apk add openssh\n"
                         "mkdir -p /root/.ssh\n"
                         "chmod 700 /root/.ssh\n"
                         "cat > /root/.ssh/authorized_keys << 'SSHEOF'\n"
                         pub-key "\n"
                         "SSHEOF\n"
                         "chmod 600 /root/.ssh/authorized_keys\n"
                         "rc-update add sshd default\n"
                         "/etc/init.d/sshd start\n"
                         (when user-script
                           (str "\n# User provisioning\n" user-script "\n")))]

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

    ;; Create apkovl tar.gz
    (fs/create-dirs tar-dir)
    (let [tar-path (str (fs/absolutize tar-dir) "/aibox.apkovl.tar.gz")]
      (p/shell {:dir apkovl-root} "tar" "czf" tar-path ".")

      ;; Create FAT disk image via hdiutil
      (p/shell "hdiutil" "create" "-size" "2m" "-fs" "MS-DOS"
               "-volname" vol-name "-o" dmg-base)
      (p/shell "hdiutil" "attach" dmg-path)
      (p/shell "cp" tar-path (str "/Volumes/" vol-name "/"))
      (p/shell "hdiutil" "detach" (str "/Volumes/" vol-name))

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
  (let [{:keys [cpus memory efi-vars-path iso-path mac]} config
        create-efi?  (not (fs/exists? efi-vars-path))
        overlay-path (create-overlay)]
    (println "Booting Alpine Linux...")
    (p/shell "vfkit"
             "--cpus" (str cpus)
             "--memory" (str memory)
             "--bootloader" (str "efi,variable-store=" efi-vars-path
                                 (when create-efi? ",create"))
             "--device" (str "usb-mass-storage,path=" iso-path ",readonly")
             "--device" (str "usb-mass-storage,path=" overlay-path ",readonly")
             "--device" (str "virtio-net,nat,mac=" mac)
             "--device" "virtio-input,keyboard"
             "--device" "virtio-input,pointing"
             "--device" "virtio-gpu,width=1024,height=768"
             "--gui")))

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
        (println "Connecting to" ip "...")
        (p/shell "ssh"
                 "-i" ssh-key-path
                 "-o" "StrictHostKeyChecking=no"
                 "-o" "UserKnownHostsFile=/dev/null"
                 (str "root@" ip)))
      (do
        (println "No VM found in DHCP leases. Is the VM running?")
        (System/exit 1)))))

(defn clean []
  (let [{:keys [data-dir iso-path]} config]
    (doseq [f (fs/list-dir data-dir)]
      (when (not= (str f) iso-path)
        (fs/delete-tree f)))
    (println "Done.")))
