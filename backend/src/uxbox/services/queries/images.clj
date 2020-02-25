;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.images
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.services.queries.teams :as teams]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]))

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::library-id ::us/uuid)

;; --- Query: Image Librarys

(def ^:private sql:libraries
  "select lib.*,
          (select count(*) from image where library_id = lib.id) as num_images
     from image_library as lib
    where lib.team_id = $1
      and lib.deleted_at is null
    order by lib.created_at desc")

(s/def ::image-libraries
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::image-libraries
  [{:keys [profile-id team-id]}]
  (db/with-atomic [conn db/pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (db/query conn [sql:libraries team-id])))


;; --- Query: Image Library

(declare retrieve-library)

(s/def ::image-library
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::image-library
  [{:keys [profile-id id]}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (retrieve-library conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      lib)))

(def ^:private sql:single-library
  "select lib.*,
          (select count(*) from image where library_id = lib.id) as num_images
     from image_library as lib
    where lib.deleted_at is null
      and lib.id = $1")

(defn- retrieve-library
  [conn id]
  (-> (db/query-one conn [sql:single-library id])
      (p/then' su/raise-not-found-if-nil)))



;; --- Query: Images (by library)

(declare retrieve-images)

(s/def ::images
  (s/keys :req-un [::profile-id ::library-id]))

;; TODO: check if we can resolve url with transducer for reduce
;; garbage generation for each request

(sq/defquery ::images
  [{:keys [profile-id library-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (retrieve-library conn library-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (-> (retrieve-images conn library-id)
          (p/then' (fn [rows]
                     (->> rows
                          (mapv #(images/resolve-urls % :path :uri))
                          (mapv #(images/resolve-urls % :thumb-path :thumb-uri)))))))))


(def ^:private sql:images
  "select img.*
     from image as img
    inner join image_library as lib on (lib.id = img.library_id)
    where img.deleted_at is null
      and img.library_id = $1
   order by created_at desc")

(defn- retrieve-images
  [conn library-id]
  (db/query conn [sql:images library-id]))



;; --- Query: Image (by ID)

(declare retrieve-image)

(s/def ::id ::us/uuid)
(s/def ::image
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::image
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [img (retrieve-image conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id img))
      (-> img
          (images/resolve-urls :path :uri)
          (images/resolve-urls :thumb-path :thumb-uri)))))

(def ^:private sql:single-image
  "select img.*,
          lib.team_id as team_id
     from image as img
    inner join image_library as lib on (lib.id = img.library_id)
    where img.deleted_at is null
      and img.id = $1
   order by created_at desc")

(defn retrieve-image
  [conn id]
  (-> (db/query-one conn [sql:single-image id])
      (p/then' su/raise-not-found-if-nil)))



