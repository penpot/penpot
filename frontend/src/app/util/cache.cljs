;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.cache
  (:require
   [app.util.time :as dt]
   [beicon.core :as rx]))

(defonce cache (atom {}))
(defonce pending (atom {}))

(defn with-cache
  [{:keys [key max-age]} observable]
  (let [entry (get @cache key)
        pending-entry (get @pending key)

        age   (when entry
                (dt/diff (dt/now)
                         (:created-at entry)))]
    (cond
      (and (some? entry) (< age max-age))
      (rx/of (:data entry))

      (some? pending-entry)
      pending-entry

      :else
      (let [subject (rx/subject)]
        (swap! pending assoc key subject)
        (->> observable
             (rx/catch #(do (rx/error! subject %)
                            (swap! pending dissoc key)
                            (rx/throw %)))
             (rx/tap
              (fn [data]
                (let [entry {:created-at (dt/now) :data data}]
                  (swap! cache assoc key entry))
                (rx/push! subject data)
                (rx/end! subject)
                (swap! pending dissoc key))))))))
