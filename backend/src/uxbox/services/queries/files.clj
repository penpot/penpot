;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.files
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]))

(declare decode-row)

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::project-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::search-term ::us/string)

;; --- Query: Files search

(def ^:private sql:search-files
  "with projects as (
     select p.*
       from project as p
      inner join team_profile_rel as tpr on (tpr.team_id = p.team_id)
      where tpr.profile_id = $1
        and p.team_id = $2
        and p.deleted_at is null
        and (tpr.is_admin = true or
             tpr.is_owner = true or
             tpr.can_edit = true)
      union
     select p.*
       from project as p
      inner join project_profile_rel as ppr on (ppr.project_id = p.id)
      where ppr.profile_id = $1
        and p.team_id = $2
        and p.deleted_at is null
        and (ppr.is_admin = true or
             ppr.is_owner = true or
             ppr.can_edit = true)
   )
   select distinct
          file.*,
          array_agg(page.id) over pages_w as pages,
          first_value(page.data) over pages_w as data
     from file
    inner join projects as pr on (file.project_id = pr.id)
     left join page on (file.id = page.file_id)
    where file.name ilike ('%' || $3 || '%')
   window pages_w as (partition by file.id order by page.created_at
                      range between unbounded preceding
                                and unbounded following)
    order by file.created_at asc")

(s/def ::search-files
  (s/keys :req-un [::profile-id ::team-id ::search-term]))

(sq/defquery ::search-files
  [{:keys [profile-id team-id search-term] :as params}]
  (-> (db/query db/pool [sql:search-files profile-id team-id search-term])
      (p/then (partial mapv decode-row))))

;; --- Query: Draft Files

(def ^:private sql:files
  "select distinct
          f.*,
          array_agg(pg.id) over pages_w as pages,
          first_value(pg.data) over pages_w as data
     from file as f
    inner join file_profile_rel as fp_r on (fp_r.file_id = f.id)
     left join page as pg on (f.id = pg.file_id)
    where fp_r.profile_id = $1
      and f.project_id = $2
      and f.deleted_at is null
      and pg.deleted_at is null
      and (fp_r.is_admin = true or
           fp_r.is_owner = true or
           fp_r.can_edit = true)
   window pages_w as (partition by f.id order by pg.ordering
                      range between unbounded preceding
                                and unbounded following)
    order by f.modified_at desc")

(s/def ::project-id ::us/uuid)
(s/def ::files
  (s/keys :req-un [::profile-id ::project-id]))

(sq/defquery ::files
  [{:keys [profile-id project-id] :as params}]
  (-> (db/query db/pool [sql:files profile-id project-id])
      (p/then (partial mapv decode-row))))

;; --- Query: File Permissions

(def ^:private sql:file-permissions
  "select fpr.is_owner,
          fpr.is_admin,
          fpr.can_edit
     from file_profile_rel as fpr
    where fpr.file_id = $1
      and fpr.profile_id = $2
   union all
   select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    inner join project as p on (p.team_id = tpr.team_id)
    inner join file as f on (p.id = f.project_id)
    where f.id = $1
      and tpr.profile_id = $2
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    inner join file as f on (f.project_id = ppr.project_id)
    where f.id = $1
      and ppr.profile_id = $2;")

(defn check-edition-permissions!
  [conn profile-id file-id]
  (-> (db/query conn [sql:file-permissions file-id profile-id])
      (p/then' seq)
      (p/then' su/raise-not-found-if-nil)
      (p/then' (fn [rows]
                 (when-not (or (some :can-edit rows)
                               (some :is-admin rows)
                               (some :is-owner rows))
                   (ex/raise :type :validation
                             :code :not-authorized))))))

;; --- Query: Images of the File

(declare retrieve-file-images)

(s/def ::file-images
  (s/keys :req-un [::profile-id ::file-id]))

(sq/defquery ::file-images
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id file-id)
    (retrieve-file-images conn params)))

(def ^:private sql:file-images
  "select fi.*
     from file_image as fi
    where fi.file_id = $1")

(defn retrieve-file-images
  [conn {:keys [file-id] :as params}]
  (let [sqlv [sql:file-images file-id]
        xf (comp (map #(images/resolve-urls % :path :uri))
                 (map #(images/resolve-urls % :thumb-path :thumb-uri)))]
    (-> (db/query conn sqlv)
        (p/then' #(into [] xf %)))))

;; --- Query: File (By ID)

(def ^:private sql:file
  "select f.*,
          array_agg(pg.id) over pages_w as pages
     from file as f
     left join page as pg on (f.id = pg.file_id)
    where f.id = $1
      and f.deleted_at is null
      and pg.deleted_at is null
   window pages_w as (partition by f.id order by pg.ordering
                      range between unbounded preceding
                                and unbounded following)")

(def ^:private sql:file-users
  "select pf.id, pf.fullname, pf.photo
     from profile as pf
    inner join file_profile_rel as fpr on (fpr.profile_id = pf.id)
    where fpr.file_id = $1
    union
   select pf.id, pf.fullname, pf.photo
     from profile as pf
    inner join team_profile_rel as tpr on (tpr.profile_id = pf.id)
    inner join project as p on (tpr.team_id = p.team_id)
    inner join file as f on (p.id = f.project_id)
    where f.id = $1")

(defn retrieve-file
  [conn id]
  (-> (db/query-one conn [sql:file id])
      (p/then' su/raise-not-found-if-nil)
      (p/then' decode-row)))

(defn retrieve-file-users
  [conn id]
  (-> (db/query conn [sql:file-users id])
      (p/then (fn [rows]
                (mapv #(images/resolve-media-uris % [:photo :photo-uri]) rows)))))


(s/def ::file-users
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::file-users
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id id)
    (retrieve-file-users conn id)))

(s/def ::file
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::file
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id id)
    (retrieve-file conn id)))


;; --- Helpers

(defn decode-row
  [{:keys [pages data] :as row}]
  (when row
    (cond-> row
      data (assoc :data (blob/decode data))
      pages (assoc :pages (vec (remove nil? pages))))))
