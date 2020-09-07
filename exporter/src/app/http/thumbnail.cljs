;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.thumbnail
  (:require
   [app.common.exceptions :as exc :include-macros true]
   [app.common.spec :as us]
   [app.http.export-bitmap :as bitmap]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]))

(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)

(s/def ::handler-params
  (s/keys :req-un [::page-id ::file-id ::object-id]))

(declare handle-single-export)
(declare handle-multiple-export)
(declare perform-export)
(declare attach-filename)

(defn thumbnail-handler
  [{:keys [params browser cookies] :as request}]
  (let [{:keys [page-id file-id object-id]} (us/conform ::handler-params params)
        params {:token (.get ^js cookies "auth-token")
                :file-id file-id
                :page-id page-id
                :object-id object-id
                :scale 0.3
                :type :jpeg}]
    (p/let [content (bitmap/screenshot-object browser params)]
      {:status 200
       :body content
       :headers {"content-type" "image/jpeg"
                 "content-length" (alength content)}})))
