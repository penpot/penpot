;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.pages
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.services.queries.files :as files]
   [uxbox.util.blob :as blob]
   [uxbox.util.sql :as sql]))

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
    (retrieve-pages conn params)))

(def ^:private sql:pages
  "select p.*
     from page as p
    where p.file_id = $1
      and p.deleted_at is null
    order by p.created_at asc")

(defn- retrieve-pages
  [conn {:keys [profile-id file-id] :as params}]
  (-> (db/query conn [sql:pages file-id])
      (p/then (partial mapv decode-row))))



;; --- Query: Single Page (By ID)

(declare retrieve-page)

(s/def ::page
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::page
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [page (retrieve-page conn id)]
      (files/check-edition-permissions! conn profile-id (:file-id page))
      page)))

(def ^:private sql:page
  "select p.* from page as p where id=$1")

(defn retrieve-page
  [conn id]
  (-> (db/query-one conn [sql:page id])
      (p/then' su/raise-not-found-if-nil)
      (p/then' decode-row)))



;; --- Query: Project Page History (by Page ID)

;; (def ^:private sql:generic-page-history
;;   "select pph.*
;;      from project_page_history as pph
;;     where pph.page_id = $2
;;       and pph.version < $3
;;     order by pph.version < desc")

;; (def ^:private sql:page-history
;;   (str "with history as (" sql:generic-page-history ")"
;;        " select * from history limit $4"))

;; (def ^:private sql:pinned-page-history
;;   (str "with history as (" sql:generic-page-history ")"
;;        " select * from history where pinned = true limit $4"))

;; (s/def ::page-id ::us/uuid)
;; (s/def ::max ::us/integer)
;; (s/def ::pinned ::us/boolean)
;; (s/def ::since ::us/integer)

;; (s/def ::project-page-snapshots
;;   (s/keys :req-un [::page-id ::user]
;;           :opt-un [::max ::pinned ::since]))

;; (defn retrieve-page-snapshots
;;   [conn {:keys [page-id user since max pinned] :or {since Long/MAX_VALUE max 10}}]
;;   (let [sql (-> (sql/from ["project_page_snapshots" "ph"])
;;                 (sql/select "ph.*")
;;                 (sql/where ["ph.user_id = ?" user]
;;                            ["ph.page_id = ?" page-id]
;;                            ["ph.version < ?" since]
;;                            (when pinned
;;                              ["ph.pinned = ?" true]))
;;                 (sql/order "ph.version desc")
;;                 (sql/limit max))]
;;     (-> (db/query conn (sql/fmt sql))
;;         (p/then (partial mapv decode-row)))))

;; (sq/defquery ::project-page-snapshots
;;   [{:keys [page-id user] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (p/do! (retrieve-page conn {:id page-id :user user})
;;            (retrieve-page-snapshots conn params))))

;; --- Helpers

(defn decode-row
  [{:keys [data metadata changes] :as row}]
  (when row
    (cond-> row
      data (assoc :data (blob/decode data))
      changes (assoc :changes (blob/decode changes)))))
