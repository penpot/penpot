;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions are met:
;;
;; * Redistributions of source code must retain the above copyright notice, this
;;   list of conditions and the following disclaimer.
;;
;; * Redistributions in binary form must reproduce the above copyright notice,
;;   this list of conditions and the following disclaimer in the documentation
;;   and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
;; AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
;; IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
;; DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
;; FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
;; DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
;; SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
;; CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
;; OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
;; OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns uxbox.util.sql
  "A composable sql helpers."
  (:refer-clojure :exclude [test update set format])
  (:require [clojure.core :as c]))

(defn- query?
  [m]
  (::query m))

(defn select
  []
  {::query true
   ::type ::select})

(defn update
  ([table]
   (update table nil))
  ([table alias]
   {::query true
    ::type ::update
    ::table [table alias]}))

(defn delete
  []
  {::query true
   ::type ::delete})

(defn insert
  [table fields]
  {::query true
   ::table table
   ::fields fields
   ::type ::insert})

(defn from
  ([m name]
   (from m name nil))
  ([m name alias]
   {:pre [(query? m)]}
   (c/update m ::from (fnil conj []) [name alias])))

(defn field
  ([m name]
   (field m name nil))
  ([m name alias]
   (c/update m ::fields (fnil conj []) [name alias])))

(defn fields
  [m & fields]
  (reduce (fn [acc item]
            (if (vector? item)
              (apply field acc item)
              (field acc item)))
          m
          fields))

(defn limit
  [m n]
  {:pre [(= (::type m) ::select)
         (query? m)]}
  (assoc m ::limit n))

(defn offset
  [m n]
  {:pre [(= (::type m) ::select)
         (query? m)]}
  (assoc m ::offset n))

(defn- join*
  [m type table alias condition]
  {:pre [(= (::type m) ::select)
         (query? m)]}
  (c/update m ::joins (fnil conj [])
               {:type type
                :name table
                :alias alias
                :condition condition}))

(defn join
  ([m table condition]
   (join m table nil condition))
  ([m table alias condition]
   {:pre [(= (::type m) ::select)
          (query? m)]}
   (join* m :inner table alias condition)))

(defn left-join
  ([m table condition]
   (left-join m table nil condition))
  ([m table alias condition]
   {:pre [(= (::type m) ::select)
          (query? m)]}
   (join* m :left table alias condition)))

(defn where
  [m condition & params]
  {:pre [(query? m)]}
  (-> m
      (c/update ::where (fnil conj []) condition)
      (cond-> (seq params)
        (c/update ::params (fnil into []) params))))

(defn set
  [m field value]
  {:pre [(query? m)]}
  (-> m
      (c/update ::assignations (fnil conj []) field)
      (c/update ::params (fnil conj []) value)))

(defn values
  [m values]
  {:pre [(query? m)]}
  (assoc ::values values))

(defn raw
  [m sql & params]
  (-> m
      (c/update ::raw (fnil conj []) sql)
      (c/update ::params (fnil into []) params)))

(defmulti format ::type)

(defn fmt
  [m]
  (into [(format m)] (::params m)))

;; --- Formating

(defn- format-fields
  [fields]
  (letfn [(transform [[name alias]]
            (if (string? alias)
              (str name " " alias)
              name))]
    (apply str (->> (map transform fields)
                    (interpose ", ")))))

(defn- format-join
  [{:keys [type name alias condition]}]
  (str (case type
         :inner "INNER JOIN "
         :left "LEFT JOIN ")
       (if alias
         (str name " " alias)
         name)
       " ON (" condition ")"))

(defn- format-joins
  [clauses]
  (apply str (->> (map format-join clauses)
                  (interpose " "))))

(defn- format-where
  [conditions]
  (when (seq conditions)
    (str "WHERE (" (apply str (interpose ") AND (" conditions)) ")")))



(defn- format-assignations
  [assignations]
  (apply str (->> (map #(str % " = ?") assignations)
                  (interpose ", "))))

(defn- format-raw
  [items]
  (when (seq items)
    (apply str (interpose " " items))))

(defmethod format ::select
  [{:keys [::fields ::from ::joins ::where]}]
  (str "SELECT "
       (format-fields fields)
       " FROM "
       (format-fields from)
       " "
       (format-joins joins)
       " "
       (format-where where)))

(defmethod format ::update
  [{:keys [::table ::assignations ::where]}]
  (str "UPDATE "
       (format-fields [table])
       " SET "
       (format-assignations assignations)
       " "
       (format-where where)))

(defmethod format ::delete
  [{:keys [::from ::where]}]
  (str "DELETE FROM "
       (format-fields from)
       " "
       (format-where where)))

(defmethod format ::insert
  [{:keys [::table ::fields ::values ::raw]}]
  (let [fsize (count fields)
        pholder (str "(" (apply str (->> (map (constantly "?") fields)
                                         (interpose ", "))) ")")]

    (str "INSERT INTO " table "(" (apply str (interpose ", " fields)) ")"
         " VALUES " (apply str (->> (map (constantly pholder) values)
                                    (interpose ", ")))
         " "
         (format-raw raw))))

;; (defn test-update
;;   []
;;   (-> (update "users" "u")
;;       (set "u.username" "foobar")
;;       (set "u.email" "niwi@niwi.nz")
;;       (where "u.id = ? AND u.deleted_at IS null" 555)))

;; (defn test-delete
;;   []
;;   (-> (delete)
;;       (from "users" "u")
;;       (where "u.id = ? AND u.deleted_at IS null" 555)))

;; (defn test-insert
;;   []
;;   (-> (insert "users" ["id", "username"])
;;       (values [[1 "niwinz"] [2 "niwibe"]])
;;       (raw "RETURNING *")))

