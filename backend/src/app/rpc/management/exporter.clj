;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.management.exporter
  (:require
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.media :refer [schema:upload]]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.storage :as sto]
   [app.util.services :as sv]))

;; ---- RPC METHOD: UPLOAD-TEMPFILE

(def ^:private
  schema:upload-tempfile-params
  [:map {:title "upload-templfile-params"}
   [:content schema:upload]])

(def ^:private
  schema:upload-tempfile-result
  [:map {:title "upload-templfile-result"}])

(sv/defmethod ::upload-tempfile
  {::doc/added "2.12"
   ::sm/params schema:upload-tempfile-params
   ::sm/result schema:upload-tempfile-result}
  [cfg {:keys [::rpc/profile-id content]}]
  (let [storage (sto/resolve cfg)
        hash    (sto/calculate-hash (:path content))
        data    (-> (sto/content (:path content))
                    (sto/wrap-with-hash hash))
        content {::sto/content data
                 ::sto/deduplicate? true
                 ::sto/touched-at (ct/in-future {:minutes 10})
                 :profile-id profile-id
                 :content-type (:mtype content)
                 :bucket "tempfile"}
        object (sto/put-object! storage content)]
    {:id (:id object)
     :uri (-> (cf/get :public-uri)
              (u/join "/assets/by-id/")
              (u/join (str (:id object))))}))
