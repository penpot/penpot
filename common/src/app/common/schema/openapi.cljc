;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.openapi
  (:require
   [clojure.set :as set]
   [cuerdas.core :as str]
   [malli.core :as m]))


(def ^:dynamic *definitions* nil)

(declare transform*)

(defmulti visit (fn [name _schema _children _options] name) :default ::default)
(defmethod visit ::default [_ _ _ _] {})
(defmethod visit :> [_ _ [value] _] {:type "number" :exclusiveMinimum value})
(defmethod visit :>= [_ _ [value] _] {:type "number" :minimum value})
(defmethod visit :< [_ _ [value] _] {:type "number" :exclusiveMaximum value})
(defmethod visit :<= [_ _ [value] _] {:type "number" :maximum value})
(defmethod visit := [_ _ [value] _] {:const value})
(defmethod visit :not= [_ _ _ _] {})

(defmethod visit :not [_ _ children _] {:not (last children)})
(defmethod visit :and [_ _ children _] {:allOf children})
(defmethod visit :or [_ _ children _] {:anyOf children})
(defmethod visit :orn [_ _ children _] {:anyOf (map last children)})

(defmethod visit ::m/val [_ _ children _] (first children))

(def ^:private required-xf
  (comp
   (filter (m/-comp not :optional second))
   (map first)
   (map str/camel)))

(defmethod visit :map [_ schema children _]
  (let [required   (into [] required-xf children)
        props      (->> children
                        (remove :hidden)
                        (mapcat (fn [[k _ s]] [(str/camel k) s]))
                        (apply array-map))

        closed?    (:closed (m/properties schema))
        object     {:type "object" :properties props}]
    (cond-> object
      (seq required)
      (assoc :required required)

      closed?
      (assoc :additionalProperties false))))

(defmethod visit :multi [_ _ children _] {:oneOf (mapv last children)})

(defn- minmax-properties
  [m schema kmin kmax]
  (merge
   m
   (-> schema
       m/properties
       (select-keys [:min :max])
       (set/rename-keys {:min kmin, :max kmax}))))

(defmethod visit :map-of [_ schema children _]
  (minmax-properties
   {:type "object",
    :additionalProperties (second children)}
   schema
   :minProperties
   :maxProperties))

(defmethod visit :vector [_ schema children _]
  (minmax-properties
   {:type "array", :items (first children)}
   schema
   :minItems
   :maxItems))

(defmethod visit :sequential [_ schema children _]
  (minmax-properties
   {:type "array", :items (first children)}
   schema
   :minItems
   :maxItems))

(defmethod visit :set [_ schema children _]
  (minmax-properties
   {:type "array", :items (first children), :uniqueItems true}
   schema
   :minItems
   :maxItems))

(defmethod visit :enum [_ _ children options] (merge (some-> (m/-infer children) (transform* options)) {:enum children}))
(defmethod visit :maybe [_ _ children _] {:oneOf (conj children {:type "null"})})
(defmethod visit :tuple [_ _ children _] {:type "array", :items children, :additionalItems false})
(defmethod visit :re [_ schema _ options] {:type "string", :pattern (first (m/children schema options))})
(defmethod visit :nil [_ _ _ _] {:type "null"})

(defmethod visit :string [_ schema _ _]
  (merge {:type "string"} (-> schema m/properties (select-keys [:min :max]) (set/rename-keys {:min :minLength, :max :maxLength}))))

(defmethod visit :int [_ schema _ _]
  (merge {:type "integer"} (-> schema m/properties (select-keys [:min :max]) (set/rename-keys {:min :minimum, :max :maximum}))))

(defmethod visit :double [_ schema _ _]
  (merge {:type "number"}
         (-> schema m/properties (select-keys [:min :max]) (set/rename-keys {:min :minimum, :max :maximum}))))

(defmethod visit :boolean [_ _ _ _] {:type "boolean"})
(defmethod visit :keyword [_ _ _ _] {:type "string"})
(defmethod visit :qualified-keyword [_ _ _ _] {:type "string"})
(defmethod visit :symbol [_ _ _ _] {:type "string"})
(defmethod visit :qualified-symbol [_ _ _ _] {:type "string"})
(defmethod visit :uuid [_ _ _ _] {:type "string" :format "uuid"})

(defmethod visit :schema [_ schema children options]
  (visit ::m/schema schema children options))

(defmethod visit ::m/schema [_ schema _ options]
  (let [result  (transform* (m/deref schema) options)
        defpath (::definitions-path options "#/definitions/")]
    (if-let [ref (m/-ref schema)]
      (let [rkey (str/concat (str/camel (namespace ref)) "$" (name ref))]
        (some-> *definitions* (swap! assoc rkey result))
        {"$ref" (str/concat defpath rkey)})
      result)))

(defmethod visit :merge [_ schema _ options] (transform* (m/deref schema) options))
(defmethod visit :union [_ schema _ options] (transform* (m/deref schema) options))
(defmethod visit :select-keys [_ schema _ options] (transform* (m/deref schema) options))

(defn- unlift-keys
  [m prefix]
  (reduce-kv #(if (= (name prefix) (namespace %2)) (assoc %1 (keyword (str/camel (name %2))) %3) %1) {} m))

(defn transform*
  [s options]
  (letfn [(walk-fn [schema _ children options]
            (let [p (merge (m/type-properties schema)
                           (m/properties schema))]
              (merge (select-keys p [:title :description :default])
                     (visit (m/type schema) schema children options)
                     (unlift-keys p :app.common.openapi))))]
    (m/walk s walk-fn options)))

(defn transform
  ([s] (transform s nil))
  ([s options]
   (let [options (assoc options ::m/walk-entry-vals true)]
     (transform* s options))))
