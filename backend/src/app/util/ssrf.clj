;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.ssrf
  "URL/host validation to prevent Server-Side Request Forgery."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cf]
   [cuerdas.core :as str])
  (:import
   com.google.common.net.InetAddresses
   java.net.InetAddress
   java.net.UnknownHostException
   java.net.URI))

(def ^:private allowed-schemes
  #{"http" "https"})

(def ^:private cloud-metadata-ips
  "Exact IP addresses for cloud metadata services."
  #{"169.254.169.254"
    "fd00:ec2::254"})

(def ^:private extra-blocked-ranges
  "CIDR ranges not covered by standard JDK InetAddress predicates.
   Each entry is [base-address prefix-length]."
  ;; Carrier-grade NAT
  [[100 64 0 0 10]
   ;; RFC 6890 / documentation / reserved
   [192 0 0 0 24]
   [192 0 2 0 24]
   [198 18 0 0 15]
   [198 51 100 0 24]
   [203 0 113 0 24]
   ;; Reserved / future-use (broadcast and above)
   [240 0 0 0 4]])

(defn- ip4-to-long
  "Convert a 4-element byte array (IPv4) to a 32-bit long."
  ^long [^bytes bs]
  (bit-or (bit-shift-left (bit-and (aget bs 0) 0xFF) 24)
          (bit-shift-left (bit-and (aget bs 1) 0xFF) 16)
          (bit-shift-left (bit-and (aget bs 2) 0xFF) 8)
          (bit-and (aget bs 3) 0xFF)))

(defn- prefix-mask
  "Return a 32-bit mask for the given prefix length."
  ^long [^long prefix-len]
  (if (zero? prefix-len)
    0
    (bit-shift-left (unsigned-bit-shift-right 0xFFFFFFFF (- 32 prefix-len)) (- 32 prefix-len))))

(defn- in-cidr4?
  "Check if an IPv4 address (as byte array) falls within a CIDR range
   specified as [a b c d prefix-len]."
  [^bytes addr [^long a ^long b ^long c ^long d ^long prefix-len]]
  (let [base   (bit-or (bit-shift-left (bit-and a 0xFF) 24)
                       (bit-shift-left (bit-and b 0xFF) 16)
                       (bit-shift-left (bit-and c 0xFF) 8)
                       (bit-and d 0xFF))
        mask   (prefix-mask prefix-len)
        ip-val (ip4-to-long addr)]
    (= (bit-and ip-val mask) (bit-and base mask))))

(defn- parse-cidr*
  "Parse a CIDR string like '10.0.0.0/8' into [a b c d prefix-len]. Throws on invalid input."
  [^String cidr]
  (let [parts      (str/split cidr #"/" 2)
        prefix-len (when (= 2 (count parts))
                     (parse-long (nth parts 1)))]
    (when-not prefix-len
      (ex/raise :type :internal
                :code :invalid-cidr
                :hint (str "invalid CIDR notation: " cidr)))
    (let [octets (str/split (first parts) #"\.")]
      (when (not= 4 (count octets))
        (ex/raise :type :internal
                  :code :invalid-cidr
                  :hint (str "invalid CIDR notation (expected IPv4): " cidr)))
      (let [[a b c d] (map parse-long octets)]
        (when (or (nil? a) (nil? b) (nil? c) (nil? d)
                  (not (<= 0 a 255)) (not (<= 0 b 255))
                  (not (<= 0 c 255)) (not (<= 0 d 255))
                  (not (<= 0 prefix-len 32)))
          (ex/raise :type :internal
                    :code :invalid-cidr
                    :hint (str "invalid CIDR notation: " cidr)))
        [a b c d prefix-len]))))

(defn parse-cidr
  "Parse a CIDR string like '10.0.0.0/8' into [a b c d prefix-len].
   Returns nil and logs a warning on invalid input."
  [^String cidr]
  (try
    (parse-cidr* cidr)
    (catch Exception _
      (l/warn :hint "ignoring invalid CIDR" :cidr cidr)
      nil)))

(defonce ^:dynamic extra-blocked-cidrs
  (into #{} (keep parse-cidr) (cf/get :ssrf-extra-blocked-cidrs #{})))

(defn- ipv6-ula?
  "Check if an IPv6 address is in the Unique Local Address range (fc00::/7)."
  [^InetAddress addr]
  (let [bs (.getAddress addr)]
    (and (>= (alength bs) 16)
         (= (bit-and (aget bs 0) 0xFE) 0xFC))))

(defn- ipv4-mapped-loopback?
  "Check if an IPv4-mapped IPv6 address maps to loopback (::ffff:127.x.x.x)."
  [^InetAddress addr]
  (let [bs (.getAddress addr)]
    (and (= (alength bs) 16)
         ;; Check it's an IPv4-mapped address: ::ffff:x.x.x.x
         (= (aget bs 10) (byte -1)) ;; 0xFF
         (= (aget bs 11) (byte -1)) ;; 0xFF
         ;; Check the embedded IPv4 is loopback (127.x.x.x)
         (= (bit-and (aget bs 12) 0xFF) 127))))

(defn- blocked-address?
  "Check if an InetAddress should be blocked. Returns true if blocked."
  [^InetAddress addr]
  (or
   (.isAnyLocalAddress addr)    ;; 0.0.0.0 or ::
   (.isLoopbackAddress addr)    ;; 127/8 or ::1
   (.isLinkLocalAddress addr)   ;; 169.254/16 or fe80::/10
   (.isSiteLocalAddress addr)   ;; 10/8, 172.16/12, 192.168/16
   (.isMulticastAddress addr)

   ;; IPv6 ULA (fc00::/7)
   (ipv6-ula? addr)

   ;; IPv4-mapped loopback
   (ipv4-mapped-loopback? addr)

   ;; Cloud metadata IPs (exact match)
   (contains? cloud-metadata-ips (.getHostAddress addr))

   ;; Extra blocked CIDRs (IPv4 only)
   (let [bs (.getAddress addr)]
     (if (= (alength bs) 4)
       (or (some #(in-cidr4? bs %) extra-blocked-ranges)
           (some #(in-cidr4? bs %) extra-blocked-cidrs))
       false))))

(defn resolve-host
  "Resolve a hostname to all InetAddress objects. Wraps InetAddress/getAllByName
   so it can be stubbed in tests."
  [^String hostname]
  (try
    (InetAddress/getAllByName hostname)
    (catch UnknownHostException _
      nil)))

(defn validate-uri
  "Validates `uri-or-string`:
    - scheme must be http or https,
    - host must resolve to at least one address, and
    - **every** resolved address must NOT be in the blocklist
      (loopback, link-local, site-local, multicast, any-local,
      cloud-metadata 169.254.169.254, IPv6 ULA fc00::/7, IPv4-mapped
       IPv6 of any blocked IPv4, plus operator-supplied CIDRs).
   When the host is an IP literal (decimal/octal/hex/IPv6) it is
   normalized via `com.google.common.net.InetAddresses` before the
   check.
   Hosts in `:ssrf-allowed-hosts` (case-insensitive exact match) bypass
   the IP check.
   Throws `ex/raise :type :validation :code :ssrf-blocked-target` with
   a hint that does NOT echo the resolved IP (avoid info leak)."
  [uri-or-string]
  (let [uri    (if (instance? URI uri-or-string)
                 uri-or-string
                 (URI. (str uri-or-string)))
        scheme (.getScheme uri)
        host   (.getHost uri)]

    ;; Validate scheme
    (when (or (nil? scheme)
              (not (contains? allowed-schemes (str/lower scheme))))
      (ex/raise :type :validation
                :code :ssrf-blocked-target
                :hint "url scheme is not allowed"))

    ;; Validate host presence
    (when (or (nil? host) (str/blank? host))
      (ex/raise :type :validation
                :code :ssrf-blocked-target
                :hint "url host is missing"))

    ;; Check allowlist
    (let [allowed-hosts (cf/get :ssrf-allowed-hosts #{})
          host-lower    (str/lower host)]

      (when-not (contains? allowed-hosts host-lower)
        ;; Normalize the host: if it looks like an IP literal, normalize it
        ;; via Guava to catch decimal/octal/hex encodings
        (let [normalized (if (InetAddresses/isInetAddress host)
                           (InetAddresses/forString host)
                           nil)
              host-to-resolve (if normalized
                                (.getHostAddress ^InetAddress normalized)
                                host)
              addresses (resolve-host host-to-resolve)]

          (when (or (nil? addresses) (zero? (alength addresses)))
            (ex/raise :type :validation
                      :code :ssrf-blocked-target
                      :hint "url host could not be resolved"))

          ;; All-or-nothing: if ANY resolved address is blocked, reject
          (when (some blocked-address? (seq addresses))
            (ex/raise :type :validation
                      :code :ssrf-blocked-target
                      :hint "url target is not allowed")))))
    (str uri)))

(defn safe-url?
  "Predicate version of `validate-uri`. Returns `true` if safe."
  [uri-or-string]
  (try
    (validate-uri uri-or-string)
    true
    (catch Exception _
      false)))
