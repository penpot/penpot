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
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.services.queries :as sq]
   [uxbox.services.queries.teams :as teams]))

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::file-id ::us/uuid)

;; --- Query: Image Librarys

(def ^:private sql:libraries
  "select lib.*,
          (select count(*) from image where library_id = lib.id) as num_images
     from image_library as lib
    where lib.team_id = ?
      and lib.deleted_at is null
    order by lib.created_at desc")

(s/def ::image-libraries
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::image-libraries
  [{:keys [profile-id team-id]}]
  (db/with-atomic [conn db/pool]
    (teams/check-read-permissions! conn profile-id team-id)
    (db/exec! conn [sql:libraries team-id])))


;; --- Query: Image Library

(declare retrieve-library)

(s/def ::image-library
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::image-library
  [{:keys [profile-id id]}]
  (db/with-atomic [conn db/pool]
    (let [lib (retrieve-library conn id)]
      (teams/check-read-permissions! conn profile-id (:team-id lib))
      lib)))

(def ^:private sql:single-library
  "select lib.*,
          (select count(*) from image where library_id = lib.id) as num_images
     from image_library as lib
    where lib.deleted_at is null
      and lib.id = ?")

(defn- retrieve-library
  [conn id]
  (let [row (db/exec-one! conn [sql:single-library id])]
    (when-not row
      (ex/raise :type :not-found))
    row))


;; --- Query: Images (by library)

(declare retrieve-images)

(s/def ::images
  (s/keys :req-un [::profile-id ::file-id]))

;; TODO: check if we can resolve url with transducer for reduce
;; garbage generation for each request

(sq/defquery ::images
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (->> (retrieve-images conn file-id)
         (mapv #(images/resolve-urls % :path :uri))
         (mapv #(images/resolve-urls % :thumb-path :thumb-uri)))))
    ;; (let [lib (retrieve-library conn file-id)]
    ;;   (teams/check-read-permissions! conn profile-id (:team-id lib))
    ;;   (->> (retrieve-images conn file-id)
    ;;        (mapv #(images/resolve-urls % :path :uri))
    ;;        (mapv #(images/resolve-urls % :thumb-path :thumb-uri))))))


(def ^:private sql:images
  "select *
     from image as img
    where img.deleted_at is null
      and img.file_id = ?
   order by created_at desc")

(defn- retrieve-images
  [conn file-id]
  (db/exec! conn [sql:images file-id]))



;; --- Query: Image (by ID)

(declare retrieve-image)

(s/def ::id ::us/uuid)
(s/def ::image
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::image
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [img (retrieve-image conn id)]
      (teams/check-read-permissions! conn profile-id (:team-id img))
      (-> img
          (images/resolve-urls :path :uri)
          (images/resolve-urls :thumb-path :thumb-uri)))))

(def ^:private sql:single-image
  "select img.*,
          file.team_id as team_id
     from image as img
    inner join file on (file.id = img.file_id)
    where img.deleted_at is null
      and img.id = ?
   order by created_at desc")

(defn retrieve-image
  [conn id]
  (let [row (db/exec-one! conn [sql:single-image id])]
    (when-not row
      (ex/raise :type :not-found))
    row))


