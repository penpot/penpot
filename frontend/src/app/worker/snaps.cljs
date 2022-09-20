;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.snaps
  (:require
   [app.util.snap-data :as sd]
   [app.worker.impl :as impl]
   [okulary.core :as l]))

(defonce state (l/atom {}))

;; Public API
(defmethod impl/handler :snaps/initialize-index
  [{:keys [data] :as message}]

  (let [pages (vals (:pages-index data))]
    (reset! state (reduce sd/add-page (sd/make-snap-data) pages)))

  nil)

(defmethod impl/handler :snaps/update-index
  [{:keys [old-page new-page] :as message}]
  (swap! state sd/update-page old-page new-page)
  nil)

(defmethod impl/handler :snaps/range-query
  [{:keys [page-id frame-id axis ranges] :as message}]

  (into []
        (comp (mapcat #(sd/query @state page-id frame-id axis %))
              (distinct))
        ranges))


