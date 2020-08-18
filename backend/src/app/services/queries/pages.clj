;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns app.services.queries.pages
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.common.pages-migrations :as pmg]
   [app.db :as db]
   [app.services.queries :as sq]
   [app.services.queries.files :as files]
   [app.util.blob :as blob]))

;; --- Helpers & Specs

(declare decode-row)

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::file-id ::us/uuid)

;; --- Query: Pages (By File ID)

(declare retrieve-pages)

(s/def ::pages
  (s/keys :req-un [::profile-id ::file-id]))

(sq/defquery ::pages
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (->> (retrieve-pages conn params)
         (mapv #(update % :data pmg/migrate-data)))))

(def ^:private sql:pages
  "select p.*
     from page as p
    where p.file_id = ?
      and p.deleted_at is null
    order by p.created_at asc")

(defn- retrieve-pages
  [conn {:keys [profile-id file-id] :as params}]
  (->> (db/exec! conn [sql:pages file-id])
       (mapv decode-row)))

;; --- Query: Single Page (By ID)

(declare retrieve-page)

(s/def ::page
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::page
  [{:keys [profile-id id] :as params}]
  (with-open [conn (db/open)]
    (let [page (retrieve-page conn id)]
      (files/check-edition-permissions! conn profile-id (:file-id page))
      (-> page
          (update :data pmg/migrate-data)))))

(def ^:private sql:page
  "select p.* from page as p where id=?")

(defn retrieve-page
  [conn id]
  (let [row (db/exec-one! conn [sql:page id])]
    (when-not row
      (ex/raise :type :not-found))
    (decode-row row)))

;; --- Query: Page Changes

(def ^:private
  sql:page-changes
  "select pc.id,
          pc.created_at,
          pc.changes,
          pc.revn
     from page_change as pc
    where pc.page_id=?
    order by pc.revn asc
    limit ?
   offset ?")


(s/def ::skip ::us/integer)
(s/def ::limit ::us/integer)

(s/def ::page-changes
  (s/keys :req-un [::profile-id ::id ::skip ::limit]))

(defn retrieve-page-changes
  [conn id skip limit]
  (->> (db/exec! conn [sql:page-changes id limit skip])
       (mapv decode-row)))

(sq/defquery ::page-changes
  [{:keys [profile-id id skip limit]}]
  (when *assert*
    (-> (db/exec! db/pool [sql:page-changes id limit skip])
        (mapv decode-row))))


;; --- Helpers

(defn decode-row
  [{:keys [data metadata changes] :as row}]
  (when row
    (cond-> row
      data (assoc :data (blob/decode data))
      changes (assoc :changes (blob/decode changes)))))
