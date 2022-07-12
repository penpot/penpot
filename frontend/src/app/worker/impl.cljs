;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.impl
  (:require
   [app.common.pages.changes :as ch]
   [app.common.transit :as t]
   [app.util.globals :refer [global]]
   [app.util.object :as obj]
   [okulary.core :as l]))

(enable-console-print!)

(defonce state (l/atom {:pages-index {}}))

;; --- Handler

(defmulti handler :cmd)

(defmethod handler :default
  [message]
  (println "Unexpected message:" message))

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
    ;; NOTE: we ignore effects here because they are designed to be
    ;; run on the main context (browser); maybe in a future we can
    ;; think in effects specifically for worker context.
    (swap! state ch/process-changes-ignoring-effects changes false)

    (let [new-page (get-in @state [:pages-index page-id])
          message  (assoc message
                          :old-page old-page
                          :new-page new-page)]
      (handler (assoc message :cmd :selection/update-index))
      (handler (assoc message :cmd :snaps/update-index)))))

(defmethod handler :configure
  [{:keys [params]}]
  (doseq [[param-key param-value] params]
    (obj/set! global param-key param-value)))
