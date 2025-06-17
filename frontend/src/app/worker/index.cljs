;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.index
  "Page index management within the worker."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes :as ch]
   [app.worker.impl :as impl]
   [okulary.core :as l]))

(defonce state (l/atom {:pages-index {}}))

(defmethod impl/handler :index/initialize-page-index
  [{:keys [page] :as message}]
  (swap! state update :pages-index assoc (:id page) page)
  (impl/handler (assoc message :cmd :selection/initialize-page-index))
  (impl/handler (assoc message :cmd :snaps/initialize-page-index)))

(defmethod impl/handler :index/update-page-index
  [{:keys [page-id changes] :as message}]

  (let [old-page (dm/get-in @state [:pages-index page-id])
        new-page (-> state
                     (swap! ch/process-changes changes false)
                     (dm/get-in [:pages-index page-id]))
        message (assoc message
                       :old-page old-page
                       :new-page new-page)]
    (impl/handler (assoc message :cmd :selection/update-page-index))
    (impl/handler (assoc message :cmd :snaps/update-page-index))))
