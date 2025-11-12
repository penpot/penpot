;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.db.sql
  (:refer-clojure :exclude [update])
  (:require
   [app.db :as-alias db]
   [clojure.string :as str]
   [next.jdbc.optional :as jdbc-opt]
   [next.jdbc.sql.builder :as sql]))

(defn kebab-case [s] (str/replace s #"_" "-"))
(defn snake-case [s] (str/replace s #"-" "_"))

(defn as-kebab-maps
  [rs opts]
  (jdbc-opt/as-unqualified-modified-maps rs (assoc opts :label-fn kebab-case)))

(def default-opts
  {:table-fn snake-case
   :column-fn snake-case
   :builder-fn as-kebab-maps})

(defn insert
  ([table key-map]
   (insert table key-map nil))
  ([table key-map opts]
   (let [opts (merge default-opts opts)
         opts (cond-> opts
                (::db/on-conflict-do-nothing? opts)
                (assoc :suffix "ON CONFLICT DO NOTHING")

                (::on-conflict-do-nothing opts)
                (assoc :suffix "ON CONFLICT DO NOTHING"))]
     (sql/for-insert table key-map opts))))

(defn insert-many
  [table cols rows opts]
  (let [opts (merge default-opts opts)
        opts (cond-> opts
               (::on-conflict-do-nothing opts)
               (assoc :suffix "ON CONFLICT DO NOTHING"))]
    (sql/for-insert-multi table cols rows opts)))

(defn select
  ([table where-params]
   (select table where-params nil))
  ([table where-params opts]
   (let [opts (merge default-opts opts)
         opts (cond-> opts
                (::order-by opts)   (assoc :order-by (::order-by opts))
                (::columns opts)    (assoc :columns (::columns opts))

                (or (::db/for-update opts)
                    (::for-update opts))
                (assoc :suffix "FOR UPDATE")

                (or (::db/for-share opts)
                    (::for-share opts))
                (assoc :suffix "FOR SHARE"))]

     (sql/for-query table where-params opts))))

(defn update
  ([table key-map where-params]
   (update table key-map where-params nil))
  ([table key-map where-params opts]
   (let [opts (into default-opts opts)
         keys (::db/return-keys opts)
         opts (if (vector? keys)
                (assoc opts :suffix (str "RETURNING " (sql/as-cols keys opts)))
                opts)]
     (sql/for-update table key-map where-params opts))))

(defn delete
  ([table where-params]
   (delete table where-params nil))
  ([table where-params opts]
   (let [opts (merge default-opts opts)
         keys (::db/return-keys opts)
         opts (if (vector? keys)
                (assoc opts :suffix (str "RETURNING " (sql/as-cols keys opts)))
                opts)]
     (sql/for-delete table where-params opts))))
