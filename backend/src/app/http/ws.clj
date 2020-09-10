;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.ws
  "Web Socket handlers"
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [ring.adapter.jetty9 :as jetty]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [app.common.spec :as us]
   [app.db :as db]
   [app.http.session :refer [wrap-session]]
   [app.services.notifications :as nf]))

(s/def ::file-id ::us/uuid)
(s/def ::session-id ::us/uuid)

(s/def ::websocket-params
  (s/keys :req-un [::file-id ::session-id]))

(def sql:retrieve-file
  "select f.id as id,
          p.team_id as team_id
     from file as f
     join project as p on (p.id = f.project_id)
    where f.id = ?")

(defn retrieve-file
  [conn id]
  (db/exec-one! conn [sql:retrieve-file id]))

(defn websocket
  [{:keys [profile-id] :as req}]
  (let [params (us/conform ::websocket-params (:params req))
        file   (retrieve-file db/pool (:file-id params))
        params (assoc params
                      :profile-id profile-id
                      :team-id (:team-id file))]
    (cond
      (not profile-id)
      {:error {:code 403 :message "Authentication required"}}

      (not file)
      {:error {:code 404 :message "File does not exists"}}

      :else
      (nf/websocket params))))

(def handler
  (-> websocket
      (wrap-session)
      (wrap-keyword-params)
      (wrap-cookies)
      (wrap-params)))
