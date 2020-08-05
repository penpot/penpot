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
   [uxbox.common.uuid :as uuid]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.services.queries.files :as files]
   [uxbox.services.queries.media :as media-queries]
   [uxbox.services.queries.pages :as pages]
   [uxbox.util.blob :as blob]
   [uxbox.util.data :as data]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::page-id ::us/uuid)

;; --- Query: Viewer Bundle (by Page ID)

(def ^:private
  sql:project
  "select p.id, p.name
     from project as p
    where p.id = ?
      and p.deleted_at is null")

(defn- retrieve-project
  [conn id]
  (db/exec-one! conn [sql:project id]))

(s/def ::share-token ::us/string)
(s/def ::viewer-bundle
  (s/keys :req-un [::page-id]
          :opt-un [::profile-id ::share-token]))

(sq/defquery ::viewer-bundle
  [{:keys [profile-id page-id share-token] :as params}]
  (db/with-atomic [conn db/pool]
    (let [page (pages/retrieve-page conn page-id)
          file (files/retrieve-file conn (:file-id page))
          images (media-queries/retrieve-media-objects conn (:file-id page) true)
          project (retrieve-project conn (:project-id file))]
      (if (string? share-token)
        (when (not= share-token (:share-token page))
          (ex/raise :type :validation
                    :code :not-authorized))
        (files/check-edition-permissions! conn profile-id (:file-id page)))
      {:page page
       :file file
       :images images
       :project project})))
