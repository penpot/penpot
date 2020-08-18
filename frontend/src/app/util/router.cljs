;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.router
  (:refer-clojure :exclude [resolve])
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as e]
   [potok.core :as ptk]
   [reitit.core :as r]
   [app.common.data :as d]
   [app.util.browser-history :as bhistory]
   [app.util.timers :as ts])
  (:import
   goog.Uri
   goog.Uri.QueryData))

;; --- Router API

(defn- parse-query-data
  [^QueryData qdata]
  (persistent!
   (reduce (fn [acc key]
             (let [values (.getValues qdata key)
                   rkey (str/keyword key)]
               (cond
                 (> (alength values) 1)
                 (assoc! acc rkey (into [] values))

                 (= (alength values) 1)
                 (assoc! acc rkey (aget values 0))

                 :else
                 acc)))
           (transient {})
           (.getKeys qdata))))

(defn resolve
  ([router id] (resolve router id {} {}))
  ([router id params] (resolve router id params {}))
  ([router id params qparams]
   (when-let [match (r/match-by-name router id params)]
     (if (empty? qparams)
       (r/match->path match)
       (let [uri (.parse goog.Uri (r/match->path match))
             qdt (.createFromMap QueryData (-> qparams
                                               (d/remove-nil-vals)
                                               (clj->js)))]
         (.setQueryData ^js uri qdt)
         (.toString ^js uri))))))

(defn create
  [routes]
  (r/router routes))

(defn initialize-router
  [routes]
  (ptk/reify ::initialize-router
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :router (create routes)))))

(defn query-params
  "Given goog.Uri, read query parameters into Clojure map."
  [^goog.Uri uri]
  (let [^js q (.getQueryData uri)]
    (->> q
         (.getKeys)
         (map (juxt keyword #(.get q %)))
         (into {}))))

(defn match
  "Given routing tree and current path, return match with possibly
  coerced parameters. Return nil if no match found."
  [router path]
  (let [uri (.parse ^js Uri path)]
    (when-let [match (r/match-by-path router (.getPath ^js uri))]
      (let [qparams (parse-query-data (.getQueryData ^js uri))
            params {:path (:path-params match) :query qparams}]
        (assoc match
               :params params
               :query-params qparams)))))

;; --- Navigate (Event)

(deftype Navigate [id params qparams replace]
  ptk/EffectEvent
  (effect [_ state stream]
    (let [router  (:router state)
          history (:history state)
          path    (resolve router id params qparams)]
      (if ^boolean replace
        (bhistory/replace-token! history path)
        (bhistory/set-token! history path)))))

(defn nav
  ([id] (nav id nil nil))
  ([id params] (nav id params nil))
  ([id params qparams] (Navigate. id params qparams false)))

(defn nav'
  ([id] (nav id nil nil))
  ([id params] (nav id params nil))
  ([id params qparams] (Navigate. id params qparams true)))

(def navigate nav)

;; --- History API

(defn initialize-history
  [on-change]
  (ptk/reify ::initialize-history
    ptk/UpdateEvent
    (update [_ state]
      (let [history (bhistory/create)]
        (bhistory/enable! history)
        (assoc state :history history)))

    ptk/EffectEvent
    (effect [_ state stream]
      (let [stoper  (rx/filter (ptk/type? ::initialize-history) stream)
            history (:history state)
            router  (:router state)]
        (ts/schedule #(on-change router (.getToken ^js history)))
        (->> (rx/create (fn [sink]
                           (let [key (e/listen history "navigate" (fn [o] (sink (.-token ^js o))))]
                             (fn []
                               (bhistory/disable! history)
                               (e/unlistenByKey key)))))
              (rx/take-until stoper)
              (rx/subs #(on-change router %)))))))




