;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.reflow
  "Promise adapter for the plugin `waitForLayoutUpdate` methods. Owns the
  argument validation, the default deadline and the rejection shape; the
  workspace only reports when its pending work has drained."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.reflow :as wrf]
   [beicon.v2.core :as rx]))

;; Ceiling for callers that pass no timeout, so a pipeline that never drains
;; its marks rejects the promise rather than leaving it unsettled.
(def ^:private default-timeout 30000)

;; Largest value a signed 32-bit timer accepts.
(def ^:private max-timeout 2147483647)

(defn- valid-timeout?
  "Checks that a plugin timeout fits a signed 32-bit timer."
  [value]
  (or (nil? value)
      (and (number? value)
           (pos? value)
           (<= value max-timeout)
           (js/Number.isFinite value))))

(defn- reject-invalid!
  [reject value]
  (let [msg (dm/str "[PENPOT PLUGIN] Value not valid: " value
                    ". Code: " :waitForLayoutUpdate)]
    (.error js/console msg)
    (reject (js/Error. msg))))

(defn shape-wait-ids
  "Ids a per-shape wait covers: the shape subtree, its ancestors, and the file
  its components sync from."
  [objects file-id id]
  (-> (into #{} (cfh/get-children-ids-with-self objects id))
      (into (cfh/get-parent-ids objects id))
      (conj file-id)
      (disj uuid/zero)))

(defn wait-for-layout-update
  "Returns a JS Promise that resolves once every id in `ids` has drained from
  the workspace pending map. A nil `ids` waits for every pending id; an empty
  one has nothing to wait for and resolves right away.

  The promise is rejected when `timeout` (ms) is not a valid timer value, or
  when it elapses first; a nil `timeout` uses `default-timeout`."
  ([timeout]
   (wait-for-layout-update nil timeout))
  ([ids timeout]
   (js/Promise.
    (fn [resolve reject]
      (if-not (valid-timeout? timeout)
        (reject-invalid! reject timeout)
        ;; Race the settle signal against the deadline; the loser is
        ;; unsubscribed. `settled` replays on subscribe, so an already drained
        ;; map wins even against a 1ms deadline.
        (->> (rx/race (->> (rx/of :timeout)
                           (rx/delay (or timeout default-timeout)))
                      (->> (wrf/settled ids)
                           (rx/map (constantly :ok))))
             (rx/take 1)
             (rx/subs!
              (fn [value]
                (if (= value :timeout)
                  (reject (js/Error. "waitForLayoutUpdate timeout"))
                  (resolve)))
              reject)))))))
