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

(ns app.util.sql
  "A composable sql helpers."
  (:refer-clojure :exclude [test update set format])
  (:require [clojure.core :as c]
            [cuerdas.core :as str]))

;; --- Low Level Helpers

(defn raw-expr
  [m]
  (cond
    (string? m)
    {::type :raw-expr
     :sql m
     :params []}

    (vector? m)
    {::type :raw-expr
     :sql (first m)
     :params (vec (rest m))}

    (and (map? m)
         (= :raw-expr (::type m)))
    m

    :else
    (throw (ex-info "unexpected input" {:m m}))))

(defn alias-expr
  [m]
  (cond
    (string? m)
    {::type :alias-expr
     :sql m
     :alias nil
     :params []}

    (vector? m)
    {::type :alias-expr
     :sql (first m)
     :alias (second m)
     :params (vec (drop 2 m))}

    :else
    (throw (ex-info "unexpected input" {:m m}))))

;; --- SQL API (Select only)

(defn from
  [name]
  {::type :query
   ::from [(alias-expr name)]
   ::order []
   ::select []
   ::join []
   ::where []})

(defn select
  [m & fields]
  (c/update m ::select into (map alias-expr fields)))

(defn limit
  [m n]
  (assoc m ::limit [(raw-expr ["LIMIT ?" n])]))

(defn offset
  [m n]
  (assoc m ::offset [(raw-expr ["OFFSET ?" n])]))

(defn order
  [m e]
  (c/update m ::order conj (raw-expr e)))

(defn- join*
  [m type table condition]
  (c/update m ::join conj
            {::type :join-expr
             :type type
             :table (alias-expr table)
             :condition (raw-expr condition)}))

(defn join
  [m table condition]
  (join* m :inner table condition))

(defn ljoin
  [m table condition]
  (join* m :left table condition))

(defn rjoin
  [m table condition]
  (join* m :right table condition))

(defn where
  [m & conditions]
  (->> (filter identity conditions)
       (reduce #(c/update %1 ::where conj (raw-expr %2)) m)))

;; --- Formating

(defmulti format-expr ::type)

(defmethod format-expr :raw-expr
  [{:keys [sql params]}]
  [sql params])

(defmethod format-expr :alias-expr
  [{:keys [sql alias params]}]
  (if alias
    [(str sql " AS " alias) params]
    [sql params]))

(defmethod format-expr :join-expr
  [{:keys [table type condition]}]
  (let [[csql cparams] (format-expr condition)
        [tsql tparams] (format-expr table)
        prefix (str/upper (name type))]
    [(str prefix " JOIN " tsql " ON (" csql ")") (into cparams tparams)]))

(defn- format-exprs
  ([items] (format-exprs items {}))
  ([items {:keys [prefix suffix join-with]
           :or {prefix ""
                suffix ""
                join-with ","}}]
   (loop [rs []
          rp []
          v (first items)
          n (rest items)]
     (if v
       (let [[s p] (format-expr v)]
         (recur (conj rs s)
                (into rp p)
                (first n)
                (rest n)))
       (if (empty? rs)
         ["" []]
         [(str prefix (str/join join-with rs) suffix) rp])))))

(defn- process-param-tokens
  [sql]
  (let [cnt (java.util.concurrent.atomic.AtomicInteger. 1)]
    (str/replace sql #"\?" (fn [& _args]
                             (str "$" (.getAndIncrement cnt))))))

(def ^:private select-formatters
  [#(format-exprs (::select %) {:prefix "SELECT "})
   #(format-exprs (::from %) {:prefix "FROM "})
   #(format-exprs (::join %) {:join-with " "})
   #(format-exprs (::where %) {:prefix "WHERE ("
                               :join-with ") AND ("
                               :suffix ")"})
   #(format-exprs (::order %) {:prefix "ORDER BY "} )
   #(format-exprs (::limit %))
   #(format-exprs (::offset %))])

(defn- collect
  [formatters qdata]
  (loop [sqls []
         params []
         f (first formatters)
         r (rest formatters)]
    (if (fn? f)
      (let [[s p] (f qdata)]
        (recur (conj sqls s)
               (into params p)
               (first r)
               (rest r)))
      [(str/join " " sqls) params])))

(defn fmt
  [qdata]
  (let [[sql params] (collect select-formatters qdata)]
    (into [(process-param-tokens sql)] params)))
