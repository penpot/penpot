;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.storage
  (:require
   [app.util.transit :as t]
   [app.util.timers :as tm]
   [app.common.exceptions :as ex]))

(defn- ^boolean is-worker?
  []
  (or (= *target* "nodejs")
      (not (exists? js/window))))

(defn- decode
  [v]
  (ex/ignoring (t/decode v)))


(defn- persist
  [storage prev curr]
  (run! (fn [key]
          (let [prev* (get prev key)
                curr* (get curr key)]
            (when (not= curr* prev*)
              (tm/schedule-on-idle
               #(if (some? curr*)
                  (.setItem ^js storage (t/encode key) (t/encode curr*))
                  (.removeItem ^js storage (t/encode key)))))))

        (into #{} (concat (keys curr)
                          (keys prev)))))

(defn- load
  [storage]
  (let [len (.-length ^js storage)]
    (reduce (fn [res index]
              (let [key (.key ^js storage index)
                    val (.getItem ^js storage key)]
                (try
                  (assoc res (t/decode key) (t/decode val))
                  (catch :default e
                    res))))
            {}
            (range len))))


(defonce storage (atom (load js/localStorage)))
(add-watch storage :persistence #(persist js/localStorage %3 %4))
