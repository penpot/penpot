;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.services.queries.viewer
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.services.queries.pages :as pages]
   [uxbox.services.queries.files :as files]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.data :as data]
   [uxbox.util.uuid :as uuid]
   [vertx.core :as vc]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::page-id ::us/uuid)

;; --- Query: Viewer Bundle (by Page ID)

(def ^:private
  sql:project
  "select p.id, p.name
     from project as p
    where p.id = $1
      and p.deleted_at is null")

(defn- retrieve-project
  [conn id]
  (db/query-one conn [sql:project id]))

(s/def ::viewer-bundle-by-page-id
  (s/keys :req-un [::profile-id ::page-id]))

(sq/defquery ::viewer-bundle-by-page-id
  [{:keys [profile-id page-id]}]
  (db/with-atomic [conn db/pool]
    (p/let [page (pages/retrieve-page conn page-id)
            file (files/retrieve-file conn (:file-id page))
            images (files/retrieve-file-images conn page)
            project (retrieve-project conn (:project-id file))]
      (files/check-edition-permissions! conn profile-id (:file-id page))
      {:page page
       :file file
       :images images
       :project project})))


;; --- Query: Viewer Bundle (By Share ID)

(declare retrieve-page-by-share-id)

(s/def ::viewer-bundle-by-share-id
  (s/keys :req-un [::share-id]
          :opt-un [::profile-id]))

(sq/defquery ::viewer-bundle-by-share-id
  [{:keys [share-id]}]
  (db/with-atomic [conn db/pool]
    (p/let [page (retrieve-page-by-share-id conn share-id)
            file (files/retrieve-file conn (:file-id page))
            images (files/retrieve-file-images conn page)
            project (retrieve-project conn (:project-id file))]
      {:page page
       :file file
       :images images
       :project project})))

(def ^:private sql:page-by-share-id
  "select p.* from page as p where share_id=$1")

(defn- retrieve-page-by-share-id
  [conn share-id]
  (-> (db/query-one conn [sql:page-by-share-id share-id])
      (p/then' su/raise-not-found-if-nil)
      (p/then' pages/decode-row)))

