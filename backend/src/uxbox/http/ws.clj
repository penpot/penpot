;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.http.ws
  "Web Socket handlers"
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [ring.adapter.jetty9 :as jetty]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.http.session :refer [wrap-session]]
   [uxbox.services.notifications :as nf]))

(s/def ::file-id ::us/uuid)
(s/def ::session-id ::us/uuid)

(s/def ::websocket-params
  (s/keys :req-un [::file-id ::session-id]))

(defn websocket
  [{:keys [profile-id] :as req}]
  (let [params (us/conform ::websocket-params (:params req))
        file   (db/get-by-id db/pool :file (:file-id params))
        params (assoc params
                      :profile-id profile-id
                      :file file)]

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
