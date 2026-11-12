;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.persist
  "Promise adapter for the plugin `waitForSave` method. Owns timeout
  validation, the short commit→buffer intake race, force-flush, and the
  save-vs-rollback race; persistence itself lives in
  `app.main.data.persistence`."
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.persistence :as dps]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Under the default MCP tool timeout (120s), leaving room for layout wait + code.
(def ^:private default-timeout 90000)

;; Primary quiescence: how long to wait for commits to enter buffer/queue
;; before treating empty persistence as "nothing to save". Not the 200ms
;; pending-status debounce.
(def ^:private intake-ms 100)

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
                    ". Code: " :waitForSave)]
    (.error js/console msg)
    (reject (js/Error. msg))))

(defn- pending-work?
  "True when persistence still holds buffered or queued commits."
  [{:keys [buffer queue]}]
  (boolean (or (seq buffer) (seq queue))))

(defn- ->js-error
  "Turns a cljs/JS failure into a JS Error for Promise rejection."
  [cause]
  (cond
    (instance? js/Error cause)
    cause

    (ex-message cause)
    (js/Error. (ex-message cause))

    :else
    (js/Error. (str cause))))

(defn- wait-intake
  "Races buffer/queue becoming non-empty against an intake deadline of
  `delay-ms` (capped by the caller to the wait timeout)."
  [delay-ms]
  (->> (rx/race (->> (rx/from-atom refs/persistence {:emit-current-value? true})
                     (rx/filter pending-work?)
                     (rx/take 1)
                     (rx/map (constantly :pending)))
                (->> (rx/of :empty)
                     (rx/delay delay-ms)))
       (rx/take 1)))

(defn- wait-save-or-rollback
  "Races a clean persist settle against rollback start or completion.
  Soft rejection emits `::rollback-rejected` before settle can publish
  empty `:saved`; `::rolled-back` may arrive later after a reload."
  [timeout-ms]
  (rx/race (->> (dps/wait-persisted-or-error timeout-ms)
                (rx/map (constantly :saved)))
           (->> st/stream
                (rx/filter (some-fn (ptk/type? ::dps/rollback-rejected)
                                    (ptk/type? ::dps/rolled-back)))
                (rx/take 1)
                (rx/mapcat (fn [_]
                             (rx/throw (js/Error. "Changes were rolled back")))))))

(defn wait-for-save
  "Returns a JS Promise that resolves once pending file changes are saved,
  or there is nothing to save after a short intake window.

  Rejects when `timeout` is invalid, the save fails, changes are rolled
  back during the wait, or the deadline elapses. A nil `timeout` uses
  `default-timeout`."
  ([] (wait-for-save nil))
  ([timeout]
   (js/Promise.
    (fn [resolve reject]
      (if-not (valid-timeout? timeout)
        (reject-invalid! reject timeout)
        (let [timeout-ms (or timeout default-timeout)
              delay-ms   (js/Math.min intake-ms timeout-ms)]
          (->> (wait-intake delay-ms)
               (rx/mapcat
                (fn [_]
                  (let [pstate @refs/persistence]
                    (cond
                      (= :error (:status pstate))
                      (dps/wait-persisted-or-error timeout-ms)

                      (pending-work? pstate)
                      (do
                        (st/emit! ::dps/force-persist)
                        (wait-save-or-rollback timeout-ms))

                      :else
                      (rx/of :empty)))))
               (rx/take 1)
               (rx/subs!
                (fn [_] (resolve))
                (fn [cause] (reject (->js-error cause)))))))))))
