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
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Leaves room for the 30s layout wait and the code itself under the
;; default MCP tool timeout (120s).
(def ^:private default-timeout 60000)

;; Primary quiescence: how long to wait for commits to enter buffer/queue
;; before treating empty persistence as "nothing to save". Not the 200ms
;; pending-status debounce.
(def ^:private intake-ms 100)

(defn- pending-work?
  "True when persistence still holds buffered or queued commits."
  [{:keys [buffer queue]}]
  (boolean (or (seq buffer) (seq queue))))

(defn- ->js-error
  "Turns a cljs/JS failure into a JS Error for Promise rejection, keeping
  the `ex-data` `:code` as the Error `code` property."
  [cause]
  (let [err  (if (instance? js/Error cause)
               cause
               (js/Error. (or (ex-message cause) (str cause))))
        code (:code (ex-data cause))]
    (when (keyword? code)
      (obj/set! err "code" (name code)))
    err))

(def ^:private earlier-failure-message
  (dm/str "Changes could not be saved: the file has a failed save that "
          "stays until the file is reopened, so no change can be confirmed "
          "as saved. Reopen the file to resume saving."))

(def ^:private retrying-message
  (dm/str "Changes could not be saved yet: saves are failing and Penpot "
          "keeps retrying them. Try again later."))

(defn- earlier-failure
  "Rejection for a wait that starts while persistence is `:error`."
  [pstate]
  (if (dps/save-retrying? pstate)
    (ex-info retrying-message {:type :persistence :code :save-retrying})
    (ex-info earlier-failure-message
             {:type :persistence
              :code (get-in pstate [:error :code] :save-failed)})))

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
                             (rx/throw (ex-info "Changes were rolled back"
                                                {:type :persistence :code :rolled-back})))))))

(defn wait-for-save
  "Returns a JS Promise that resolves once pending file changes are saved,
  or there is nothing to save after a short intake window.

  Rejects when `timeout` is invalid, the save fails (also a failure from
  before the call: a terminal one stays until the file is reopened),
  changes are rolled back during the wait, or the deadline elapses. The
  Error `code` is `save-timeout`, `rolled-back`, `save-retrying` (an
  earlier failure Penpot keeps retrying), or the failure code
  (`save-failed` unless the backend gave a more specific one). A nil
  `timeout` uses `default-timeout`."
  ([] (wait-for-save nil))
  ([timeout]
   (js/Promise.
    (fn [resolve reject]
      (if-not (u/valid-timeout? timeout)
        (u/reject-not-valid reject :waitForSave timeout)
        (let [timeout-ms (or timeout default-timeout)
              delay-ms   (js/Math.min intake-ms timeout-ms)]
          (->> (wait-intake delay-ms)
               (rx/mapcat
                (fn [_]
                  (let [pstate @refs/persistence]
                    (cond
                      (= :error (:status pstate))
                      (rx/throw (earlier-failure pstate))

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
