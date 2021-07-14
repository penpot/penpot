;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.cache
  (:require
   [app.util.time :as dt]
   [beicon.core :as rx]))

(defonce cache (atom {}))

(defn with-cache
  [{:keys [key max-age]} observable]
  (let [entry (get @cache key)
        age   (when entry
                (dt/diff (dt/now)
                         (:created-at entry)))]
    (if (and (some? entry) (< age max-age))
      (rx/of (:data entry))
      (->> observable
           (rx/tap
            (fn [data]
              (let [entry {:created-at (dt/now) :data data}]
                (swap! cache assoc key entry))))))))
