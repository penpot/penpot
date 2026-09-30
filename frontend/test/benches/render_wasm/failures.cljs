;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.failures
  "Helpers for browser failures.")

(def ^:const max-cause-depth
  "How many cause levels the failure cause keeps: the phase wrapper, the
  WASM-error mapping and the original failure."
  3)

(def ^:const max-cause-chars
  "Hard cap for any string in the failure cause, to avoid bloating the bridge
  payload."
  500)

(def ^:const unrenderable-value
  "Fallback for a cause value `pr-str` cannot render."
  "unrenderable value")

(def ^:const unrenderable-cause-text
  "Fallback message when a cause level cannot be rendered."
  "unrenderable cause")

(defn stale?
  "True when `cause` is the superseded-owner marker from `guard-current!`."
  [cause]
  (true? (::stale (ex-data cause))))

(defn fail-data
  [m]
  (assoc m :status "failed"))

(defn- truncate-cause-str
  "Truncates `s` to `max-cause-chars` without splitting a unicode surrogate pair.
  Never throws."
  [s]
  (try
    (let [text (str s)]
      (if (<= (count text) max-cause-chars)
        text
        ;; check whether truncating the string at max-cause-chars would split a
        ;; Unicode character represented by a surrogate pair (e.g. some emoji)
        (let [cut  (subs text 0 max-cause-chars)
              last (.charCodeAt cut (dec max-cause-chars))]
          (if (and (>= last 0xD800)
                   (<= last 0xDBFF)
                   (let [nxt (.charCodeAt text max-cause-chars)]
                     (and (>= nxt 0xDC00) (<= nxt 0xDFFF))))
            (subs cut 0 (dec max-cause-chars))
            cut))))
    (catch :default _
      unrenderable-value)))

(defn- sanitize-cause-value
  "Keeps Transit numbers and other plain-data leaves as-is, stringifies
  anything else with `pr-str` truncation. Never throws."
  [value]
  (try
    (cond
      (or (nil? value) (boolean? value) (keyword? value))
      value

      (string? value)
      (truncate-cause-str value)

      (number? value)
      value

      :else
      (truncate-cause-str (pr-str value)))
    (catch :default _
      unrenderable-value)))

(defn- describe-cause-level
  "Plain-data projection of one `ex-cause` link. Keeps `:message` plus the
  allowlisted WASM details, sanitized. Never throws."
  [cause]
  (try
    (let [message (try (ex-message cause) (catch :default _ nil))
          data    (try (ex-data cause) (catch :default _ nil))
          text    (cond
                    (string? message) message
                    (string? cause)   cause
                    (nil? cause)      "unknown failure"
                    :else             (pr-str cause))
          out     {:message (sanitize-cause-value text)}]
      (if (map? data)
        (reduce (fn [m k]
                  (if (contains? data k)
                    (assoc m k (sanitize-cause-value (get data k)))
                    m))
                out
                [:fn :code :type :hint])
        out))
    (catch :default _
      {:message unrenderable-cause-text})))

(defn describe-cause
  "Walks at most `max-cause-depth` cause levels, projecting each with
  `describe-cause-level`. Always returns plain data, never throws."
  [cause]
  (try
    (loop [current cause
           depth   0
           acc     []]
      (if (or (nil? current) (>= depth max-cause-depth))
        (if (seq acc)
          acc
          [{:message "unknown failure"}])
        (let [level (describe-cause-level current)
              next  (try (ex-cause current) (catch :default _ nil))]
          (recur next (inc depth) (conj acc level)))))
    (catch :default _
      [{:message unrenderable-cause-text}])))
