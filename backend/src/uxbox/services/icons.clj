;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.icons
  "Icons library related services."
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.core :as sv]
   [uxbox.util.blob :as blob]
   [uxbox.util.exceptions :as ex]
   [uxbox.util.spec :as us]
   [uxbox.util.uuid :as uuid]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::user ::us/uuid)
(s/def ::collection-id (s/nilable ::us/uuid))
(s/def ::width ::us/integer)
(s/def ::height ::us/integer)

(s/def ::view-box
  (s/and (s/coll-of number?)
         #(= 4 (count %))
         vector?))

(s/def ::content ::us/string)
(s/def ::mimetype ::us/string)

(s/def ::metadata
  (s/keys :opt-un [::width ::height ::view-box ::mimetype]))

(defn- decode-icon-row
  [{:keys [metadata] :as row}]
  (when row
    (cond-> row
      metadata (assoc :metadata (blob/decode metadata)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Query: Collections

(def ^:private icons-collections-sql
  "select *,
          (select count(*) from icons where collection_id = ic.id) as num_icons
     from icons_collections as ic
    where (ic.user_id = $1 or
           ic.user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and ic.deleted_at is null
    order by ic.created_at desc")

(s/def ::icons-collections
  (s/keys :req-un [::user]))

(sv/defquery :icons-collections
  {:doc "Retrieve all icons collections for current user."
   :spec ::icons-collections}
  [{:keys [user] :as params}]
  (let [sqlv [icons-collections-sql user]]
    (db/query db/pool sqlv)))

;; --- List Icons

(def ^:private icons-by-collection-sql
  "select *
     from icons as i
    where (i.user_id = $1 or
           i.user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and i.deleted_at is null
      and case when $2::uuid is null then i.collection_id is null
               else i.collection_id = $2::uuid
          end
    order by i.created_at desc")

(s/def ::icons-by-collection
  (s/keys :req-un [::user]
          :opt-un [::collection-id]))

(sv/defquery :icons-by-collection
  {:doc "Retrieve icons for specified collection."
   :spec ::icons-by-collection}
  [{:keys [user collection-id] :as params}]
  (let [sqlv [icons-by-collection-sql user collection-id]]
    (-> (db/query db/pool sqlv)
        (p/then' #(mapv decode-icon-row %)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Mutation: Create Collection

(s/def ::create-icons-collection
  (s/keys :req-un [::user ::name]
          :opt-un [::id]))

(sv/defmutation :create-icons-collection
  {:doc "Create a new collection of icons."
   :spec ::create-icons-collection}
  [{:keys [id user name] :as params}]
  (let [id  (or id (uuid/next))
        sql "insert into icons_collections (id, user_id, name)
             values ($1, $2, $3) returning *"]
    (db/query-one db/pool [sql id user name])))

;; --- Mutation: Update Collection

(s/def ::update-icons-collection
  (s/keys :req-un [::user ::name ::id]))

(sv/defmutation :update-icons-collection
  {:doc "Update a collection of icons."
   :spec ::update-icons-collection}
  [{:keys [id user name] :as params}]
  (let [sql "update icons_collections
                set name = $3
              where id = $1
                and user_id = $2
             returning *"]
    (-> (db/query-one db/pool [sql id user name])
        (p/then' sv/raise-not-found-if-nil))))

;; --- Copy Icon

(declare create-icon)

(defn- retrieve-icon
  [conn {:keys [user id]}]
  (let [sql "select * from icons
              where id = $1
                and deleted_at is null
                and (user_id = $2 or
                     user_id = '00000000-0000-0000-0000-000000000000'::uuid)"]
  (-> (db/query-one conn [sql id user])
      (p/then' sv/raise-not-found-if-nil))))

(s/def ::copy-icon
  (s/keys :req-un [:us/id ::collection-id ::user]))

(sv/defmutation :copy-icon
  {:doc "Copy an icon from one collection to other."
   :spec ::copy-icon}
  [{:keys [user id collection-id] :as params}]
  (db/with-atomic [conn db/pool]
    (-> (retrieve-icon conn {:user user :id id})
        (p/then (fn [icon]
                  (let [icon (-> (dissoc icon :id)
                                 (assoc :collection-id collection-id))]
                    (create-icon conn icon)))))))

;; --- Delete Collection

(s/def ::delete-icons-collection
  (s/keys :req-un [::user ::id]))

(sv/defmutation :delete-icons-collection
  {:doc "Delete a collection of icons."
   :spec ::delete-icons-collection}
  [{:keys [user id] :as params}]
  (let [sql "update icons_collections
                set deleted_at = clock_timestamp()
              where id = $1
                and user_id = $2
             returning id"]
    (-> (db/query-one db/pool [sql id user])
        (p/then' sv/raise-not-found-if-nil)
        (p/then' sv/constantly-nil))))

;; --- Mutation: Create Icon (Upload)

(def ^:private create-icon-sql
  "insert into icons (user_id, name, collection_id, content, metadata)
   values ($1, $2, $3, $4, $5) returning *")

(defn create-icon
  [conn {:keys [id user name collection-id metadata content]}]
  (let [id  (or id (uuid/next))
        sqlv [create-icon-sql user name
              collection-id
              content
              (blob/encode metadata)]]
    (-> (db/query-one conn sqlv)
        (p/then' decode-icon-row))))

(s/def ::create-icon
  (s/keys :req-un [::user ::name ::metadata ::content]
          :opt-un [::id ::collection-id]))

(sv/defmutation :create-icon
  {:doc "Create (upload) a new icon."
   :spec ::create-icon}
  [params]
  (create-icon db/pool params))

;; --- Mutation: Update Icon

(s/def ::update-icon
  (s/keys :req-un [::id ::user ::name ::collection-id]))

(sv/defmutation :update-icon
  {:doc "Update an icon entry."
   :spec ::update-icon}
  [{:keys [id name user collection-id] :as params}]
  (let [sql "update icons
                set name = $1,
                    collection_id = $2
              where id = $3
                and user_id = $4
             returning *"]
    (-> (db/query-one db/pool [sql name collection-id id user])
        (p/then' sv/raise-not-found-if-nil))))

;; --- Mutation: Delete Icon

(s/def ::delete-icon
  (s/keys :req-un [::user ::id]))

(sv/defmutation :delete-icon
  {:doc "Delete an icon entry."
   :spec ::delete-icon}
  [{:keys [id user] :as params}]
  (let [sql "update icons
                set deleted_at = clock_timestamp()
              where id = $1
                and user_id = $2
             returning id"]
    (-> (db/query-one db/pool [sql id user])
        (p/then' sv/raise-not-found-if-nil)
        (p/then' sv/constantly-nil))))
