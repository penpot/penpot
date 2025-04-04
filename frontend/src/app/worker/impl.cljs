;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.impl
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes :as ch]
   [app.common.logging :as log]
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

(defmethod handler :initialize-page-index
  [{:keys [page] :as message}]
  (swap! state update :pages-index assoc (:id page) page)
  (handler (assoc message :cmd :selection/initialize-page-index))
  (handler (assoc message :cmd :snaps/initialize-page-index)))

(defmethod handler :update-page-index
  [{:keys [page-id changes] :as message}]

  (let [old-page (dm/get-in @state [:pages-index page-id])
        new-page (-> state
                     (swap! ch/process-changes changes false)
                     (dm/get-in [:pages-index page-id]))
        message (assoc message
                       :old-page old-page
                       :new-page new-page)]
    (handler (assoc message :cmd :selection/update-page-index))
    (handler (assoc message :cmd :snaps/update-page-index))))

(defmethod handler :configure
  [{:keys [config]}]
  (log/info :hint "configure worker" :keys (keys config))

  (when-let [public-uri (get config :public-uri)]
    (set! cf/public-uri public-uri))

  (when-let [version (get config :version)]
    (set! cf/version version))

  (when-let [build-date (get config :build-data)]
    (set! cf/build-date build-date))

  nil)
