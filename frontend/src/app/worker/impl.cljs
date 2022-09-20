;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.impl
  (:require
   [app.common.logging :as log]
   [app.common.pages.changes :as ch]
   [app.common.transit :as t]
   [app.config :as cf]
   [okulary.core :as l]))

(log/set-level! :info)

(enable-console-print!)

(defonce state (l/atom {:pages-index {}}))

;; --- Handler

(defmulti handler :cmd)

(defmethod handler :default
  [message]
  (log/warn :hint "unexpected message" :message message))

(defmethod handler :echo
  [message]
  message)

(defmethod handler :initialize-indices
  [{:keys [file-raw] :as message}]

  (let [data (-> (t/decode-str file-raw) :data)
        message (assoc message :data data)]
    (reset! state data)
    (handler (-> message
                 (assoc :cmd :selection/initialize-index)))
    (handler (-> message
                 (assoc :cmd :snaps/initialize-index)))))

(defmethod handler :update-page-indices
  [{:keys [page-id changes] :as message}]

  (let [old-page (get-in @state [:pages-index page-id])]
    (swap! state ch/process-changes changes false)

    (let [new-page (get-in @state [:pages-index page-id])
          message (assoc message
                         :old-page old-page
                         :new-page new-page)]
      (handler (-> message
                   (assoc :cmd :selection/update-index)))
      (handler (-> message
                   (assoc :cmd :snaps/update-index))))))

(defmethod handler :configure
  [{:keys [key val]}]
  (log/info :hint "configure worker" :key key :val val)
  (case key
    :public-uri
    (reset! cf/public-uri val)))
