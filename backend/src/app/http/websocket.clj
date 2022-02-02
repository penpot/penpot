;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.websocket
  "A penpot notification service for file cooperative edition."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.websocket :as ws]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [yetti.websocket :as yws]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WEBSOCKET HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare send-presence!)

(defmulti handle-message
  (fn [_wsp message] (:type message)))

(defmethod handle-message :connect
  [wsp _]
  (let [{:keys [msgbus file-id team-id session-id ::ws/output-ch]} @wsp
        sub-ch (a/chan (a/dropping-buffer 32))]

    (swap! wsp assoc :sub-ch sub-ch)

    ;; Start a subscription forwarding goroutine
    (a/go-loop []
      (when-let [val (a/<! sub-ch)]
        (when-not (= (:session-id val) session-id)
          ;; If we receive a connect message of other user, we need
          ;; to send an update presence to all participants.
          (when (= :connect (:type val))
            (a/<! (send-presence! @wsp :presence)))

          ;; Then, just forward the message
          (a/>! output-ch val))
        (recur)))

    (a/go
      (a/<! (msgbus :sub {:topics [file-id team-id] :chan sub-ch}))
      (a/<! (send-presence! @wsp :connect)))))

(defmethod handle-message :disconnect
  [wsp _]
  (a/close! (:sub-ch @wsp))
  (send-presence! @wsp :disconnect))

(defmethod handle-message :keepalive
  [_ _]
  (a/go :nothing))

(defmethod handle-message :pointer-update
  [wsp message]
  (let [{:keys [profile-id file-id session-id msgbus]} @wsp]
    (msgbus :pub {:topic file-id
                  :message (assoc message
                                  :profile-id profile-id
                                  :session-id session-id)})))

(defmethod handle-message :default
  [_ message]
  (a/go
    (l/log :level :warn
           :msg "received unexpected message"
           :message message)))

;; --- IMPL

(defn- send-presence!
  ([ws] (send-presence! ws :presence))
  ([{:keys [msgbus session-id profile-id file-id]} type]
   (msgbus :pub {:topic file-id
                 :message {:type type
                           :session-id session-id
                           :profile-id profile-id}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare retrieve-file)

(s/def ::msgbus fn?)
(s/def ::file-id ::us/uuid)
(s/def ::session-id ::us/uuid)

(s/def ::handler-params
  (s/keys :req-un [::file-id ::session-id]))

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::msgbus ::db/pool ::mtx/metrics ::wrk/executor]))

(defmethod ig/init-key ::handler
  [_ {:keys [metrics pool] :as cfg}]
  (let [metrics {:connections (get-in metrics [:definitions :websocket-active-connections])
                 :messages    (get-in metrics [:definitions :websocket-messages-total])
                 :sessions    (get-in metrics [:definitions :websocket-session-timing])}]
    (fn [{:keys [profile-id params] :as req}]
      (let [params (us/conform ::handler-params params)
            file   (retrieve-file pool (:file-id params))
            cfg    (-> (merge cfg params)
                       (assoc :profile-id profile-id)
                       (assoc :team-id (:team-id file))
                       (assoc ::ws/metrics metrics))]

        (when-not profile-id
          (ex/raise :type :authentication
                    :hint "Authentication required."))

        (when-not file
          (ex/raise :type :not-found
                    :code :object-not-found))

        (when-not (yws/upgrade-request? req)
          (ex/raise :type :validation
                    :code :websocket-request-expected
                    :hint "this endpoint only accepts websocket connections"))

        (->> (ws/handler handle-message cfg)
             (yws/upgrade req))))))

(def ^:private
  sql:retrieve-file
  "select f.id as id,
          p.team_id as team_id
     from file as f
     join project as p on (p.id = f.project_id)
    where f.id = ?")

(defn- retrieve-file
  [conn id]
  (db/exec-one! conn [sql:retrieve-file id]))

