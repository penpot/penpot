;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.storage
  (:require
   [app.common.transit :as t]
   [app.util.globals :as g]
   [app.util.timers :as tm]))

(defn- persist
  [storage prev curr]
  (run! (fn [key]
          (let [prev* (get prev key)
                curr* (get curr key)]
            (when (not= curr* prev*)
              (tm/schedule-on-idle
               #(if (some? curr*)
                  (.setItem ^js storage (t/encode-str key) (t/encode-str curr*))
                  (.removeItem ^js storage (t/encode-str key)))))))

        (into #{} (concat (keys curr)
                          (keys prev)))))

(defn- load
  [storage]
  (when storage
    (let [len (.-length ^js storage)]
      (reduce (fn [res index]
                (let [key (.key ^js storage index)
                      val (.getItem ^js storage key)]
                  (try
                    (assoc res (t/decode-str key) (t/decode-str val))
                    (catch :default _e
                      res))))
              {}
              (range len)))))


(defonce storage (atom (load (unchecked-get g/global "localStorage"))))
(add-watch storage :persistence #(persist js/localStorage %3 %4))
