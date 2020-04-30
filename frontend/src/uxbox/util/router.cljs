;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.router
  (:refer-clojure :exclude [resolve])
  (:require
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [reitit.core :as r]
   [goog.events :as e]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.util.browser-history :as bhistory]
   [uxbox.common.data :as d])
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
         (.setQueryData uri qdt)
         (.toString uri))))))

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
  (let [q (.getQueryData uri)]
    (->> q
         (.getKeys)
         (map (juxt keyword #(.get q %)))
         (into {}))))

(defn match
  "Given routing tree and current path, return match with possibly
  coerced parameters. Return nil if no match found."
  [router path]
  (let [uri (.parse Uri path)]
    (when-let [match (r/match-by-path router (.getPath uri))]
      (let [qparams (parse-query-data (.getQueryData uri))
            params {:path (:path-params match) :query qparams}]
        (assoc match
               :params params
               :query-params qparams)))))

;; --- Navigate (Event)

(deftype Navigate [id params qparams]
  ptk/EffectEvent
  (effect [_ state stream]
    (let [router  (:router state)
          history (:history state)
          path    (resolve router id params qparams)]
      (bhistory/set-token! history path))))

(defn nav
  ([id] (nav id nil nil))
  ([id params] (nav id params nil))
  ([id params qparams]
   {:pre [(keyword? id)]}
   (Navigate. id params qparams)))

(def navigate nav)

;; --- History API

(defn initialize-history
  [on-change]
  (ptk/reify ::initialize-history
    ptk/UpdateEvent
    (update [_ state]
      (let [history (bhistory/create)]
        (assoc state :history history)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper  (rx/filter (ptk/type? ::initialize-history) stream)
            history (:history state)
            router  (:router state)]
        (->> (rx/create (fn [sink]
                          (let [key (e/listen history "navigate" #(sink (.-token %)))]
                            (bhistory/enable! history)
                            (fn []
                              (bhistory/disable! history)
                              (e/unlistenByKey key)))))
             (rx/map #(on-change router %))
             (rx/take-until stoper))))))




