;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.router
  (:require [reitit.core :as r]
            [cuerdas.core :as str]
            [potok.core :as ptk]
            [uxbox.util.html.history :as html-history])
  (:import goog.Uri
           goog.Uri.QueryData))

(defonce +router+ nil)

;; --- API

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

(defn- resolve-url
  ([router id] (resolve-url router id {} {}))
  ([router id params] (resolve-url router id params {}))
  ([router id params qparams]
   (when-let [match (r/match-by-name router id params)]
     (if (empty? qparams)
       (r/match->path match)
       (let [uri (.parse goog.Uri (r/match->path match))
             qdt (.createFromMap QueryData (clj->js qparams))]
         (.setQueryData uri qdt)
         (.toString uri))))))

(defn init
  [routes]
  (r/router routes))

(defn query-params
  "Given goog.Uri, read query parameters into Clojure map."
  [^goog.Uri uri]
  (let [q (.getQueryData uri)]
    (->> q
         (.getKeys)
         (map (juxt keyword #(.get q %)))
         (into {}))))

(defn navigate!
  ([router id] (navigate! router id {} {}))
  ([router id params] (navigate! router id params {}))
  ([router id params qparams]
   (-> (resolve-url router id params qparams)
       (html-history/set-path!))))

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

(defn route-for
  "Given a location handler and optional parameter map, return the URI
  for such handler and parameters."
  ([id] (route-for id {}))
  ([id params]
   (str (some-> +router+ (resolve-url id params)))))

;; --- Navigate (Event)

(deftype Navigate [id params qparams]
  ptk/EffectEvent
  (effect [_ state stream]
    (let [router (:router state)]
      ;; (prn "Navigate:" id params qparams "| Match:" (resolve-url router id params qparams))
      (navigate! router id params qparams))))

(defn nav
  ([id] (nav id nil nil))
  ([id params] (nav id params nil))
  ([id params qparams]
   {:pre [(keyword? id)]}
   (Navigate. id params qparams)))

(def navigate nav)

