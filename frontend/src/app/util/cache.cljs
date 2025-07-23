;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.cache
  (:require
   [app.common.time :as ct]
   [beicon.v2.core :as rx]))

(defonce cache (atom {}))
(defonce pending (atom {}))

(defn with-cache
  [{:keys [key max-age]} observable]
  (let [entry (get @cache key)
        pending-entry (get @pending key)
        age   (when entry
                (ct/diff-ms (:created-at entry) (ct/now)))]
    (cond
      (and (some? entry) (< age max-age))
      (rx/of (:data entry))

      (some? pending-entry)
      pending-entry

      :else
      (let [subject (rx/subject)]
        (do
          (swap! pending assoc key subject)

          (rx/subscribe
           observable

           (fn [data]
             (let [entry {:created-at (ct/now) :data data}]
               (swap! cache assoc key entry))
             (swap! pending dissoc key)
             (rx/push! subject data)
             (rx/end! subject))

           #(do
              (swap! pending dissoc key)
              (rx/error! subject %))))
        subject))))
