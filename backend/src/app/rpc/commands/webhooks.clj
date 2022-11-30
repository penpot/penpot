;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.webhooks
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.client :as http]
   [app.rpc.doc :as-alias doc]
   [app.rpc.queries.teams :refer [check-edition-permissions!]]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

;; --- Mutation: Create Webhook

(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::uri ::us/not-empty-string)
(s/def ::is-active ::us/boolean)
(s/def ::mtype
  #{"application/json"
    "application/x-www-form-urlencoded"
    "application/transit+json"})

(s/def ::create-webhook
  (s/keys :req-un [::profile-id ::team-id ::uri ::mtype]
          :opt-un [::is-active]))

;; FIXME: validate
;; FIXME: default ratelimit
;; FIXME: quotes

(defn- validate-webhook!
  [cfg whook params]
  (letfn [(handle-exception [exception]
            (cond
              (instance? java.util.concurrent.CompletionException exception)
              (handle-exception (ex/cause exception))

              (instance? javax.net.ssl.SSLHandshakeException exception)
              (ex/raise :type :validation
                        :code :webhook-validation
                        :hint "ssl-validaton")

              :else
              (ex/raise :type :validation
                        :code :webhook-validation
                        :hint "unknown"
                        :cause exception)))

          (handle-response [{:keys [status] :as response}]
            (when (not= status 200)
              (ex/raise :type :validation
                        :code :webhook-validation
                        :hint (str/ffmt "unexpected-status-%" (:status response)))))]

    (if (not= (:uri whook) (:uri params))
      (->> (http/req! cfg {:method :head
                           :uri (:uri params)
                           :timeout (dt/duration "2s")})
           (p/hmap (fn [response exception]
                     (if exception
                       (handle-exception exception)
                       (handle-response response)))))
      (p/resolved nil))))

(sv/defmethod ::create-webhook
  {::doc/added "1.17"}
  [{:keys [::db/pool ::wrk/executor] :as cfg} {:keys [profile-id team-id uri mtype is-active] :as params}]
  (check-edition-permissions! pool profile-id team-id)
  (letfn [(insert-webhook [_]
            (db/insert! pool :webhook
                        {:id (uuid/next)
                         :team-id team-id
                         :uri uri
                         :is-active is-active
                         :mtype mtype}))]
    (->> (validate-webhook! cfg nil params)
         (p/fmap executor insert-webhook))))

(s/def ::update-webhook
  (s/keys :req-un [::id ::uri ::mtype ::is-active]))

(sv/defmethod ::update-webhook
  {::doc/added "1.17"}
  [{:keys [::db/pool ::wrk/executor] :as cfg} {:keys [profile-id id uri mtype is-active] :as params}]
  (let [whook     (db/get pool :webhook {:id id})
        update-fn (fn [_]
                    (db/update! pool :webhook
                                {:uri uri
                                 :is-active is-active
                                 :mtype mtype
                                 :error-code nil
                                 :error-count 0}
                                {:id id}))]
    (check-edition-permissions! pool profile-id (:team-id whook))

    (->> (validate-webhook! cfg whook params)
         (p/fmap executor update-fn))))

(s/def ::delete-webhook
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::delete-webhook
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id id]}]
  (db/with-atomic [conn pool]
    (let [whook (db/get conn :webhook {:id id})]
      (check-edition-permissions! conn profile-id (:team-id whook))
      (db/delete! conn :webhook {:id id})
      nil)))
