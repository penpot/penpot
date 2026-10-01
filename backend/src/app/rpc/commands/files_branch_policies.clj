;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.files-branch-policies
  "The branch-merge policy switch: reads the policies file and binds its
  choices around the branch computations that read them.

  The file (`resources/app/branch-merge-policies.edn` by default,
  `:branch-merge-policies-file` to point elsewhere) is a commented EDN
  map with one key per policy. It is re-read whenever its mtime changes,
  so a developer flips a policy by editing the file and re-running the
  compare or merge — no restart. A key the file leaves out keeps its
  default (`bm/default-policies`); a file that is unreadable, is not a
  map, names an unknown policy or picks a value outside
  `bm/policy-values` logs a warning and falls back to the defaults
  entirely.

  `with-policies` binds `bm/*policies*` to the effective policies around
  a computation; `policies-hash` turns them into a cache key component,
  so summaries computed under different policies never share a cache
  entry."
  (:require
   [app.common.files.branch-merge :as bm]
   [app.common.logging :as l]
   [app.config :as cf]
   [clojure.edn :as edn]
   [datoteka.fs :as fs]))

(def ^:private state
  "Cache of the last load: the file identity (path + mtime) and the
  policies it yielded. A file is re-read only when its mtime changes."
  (atom nil))

(defn- policies-path
  "The configured policies file, or nil when it does not exist (an
  unconfigured instance runs the defaults)."
  []
  (let [path (cf/get :branch-merge-policies-file)]
    (when (and (some? path)
               (fs/exists? path)
               (fs/regular-file? path))
      (fs/path path))))

(defn- warn-invalid!
  [path reason]
  (l/warn :hint "invalid branch merge policies file"
          :path (str path)
          :reason reason
          :fallback "app.common.files.branch-merge/default-policies"
          ::l/sync? true))

(defn- read-policies
  "The policies map the file at `path` names — the entries it sets
  overlay `bm/default-policies` — or nil after a warning when its
  content is not a valid policies map."
  [path]
  (try
    (let [data (edn/read-string (slurp path))]
      (if (and (map? data)
               (every? (fn [[key value]]
                         (contains? (get bm/policy-values key) value))
                       data))
        (merge bm/default-policies data)
        (do
          (warn-invalid! path "not a map of policy keys to allowed values")
          nil)))
    (catch Throwable cause
      (warn-invalid! path (ex-message cause))
      nil)))

(defn effective-policies
  "The policies currently in effect: what the policies file names,
  re-read on mtime change, `bm/default-policies` when the file is
  absent or invalid (after a warning)."
  []
  (let [path (policies-path)]
    (if (nil? path)
      bm/default-policies
      (let [mtime (inst-ms (fs/last-modified-time path))
            cache @state]
        (if (and (= path (::path cache))
                 (= mtime (::mtime cache)))
          (::policies cache)
          (let [policies (or (read-policies path) bm/default-policies)]
            (reset! state {::path path ::mtime mtime ::policies policies})
            policies))))))

(defn policies-hash
  "Hash of the policies currently bound (`bm/*policies*`), for cache
  keys: two different policies must never share a cached summary, and a
  key must hash exactly the policies its value was computed under."
  []
  (hash bm/*policies*))

(defmacro with-policies
  "Evaluate `body` with `bm/*policies*` bound to the effective
  policies — the policies file, re-read on mtime change."
  [& body]
  `(binding [bm/*policies* (effective-policies)]
     ~@body))
