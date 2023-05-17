;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.desc-native
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [malli.core :as m]))

(declare describe*)

(defmulti visit (fn [name _schema _children _options] name) :default ::default)

(defmethod visit ::default [_ schema _ options]
  (m/form schema options))

(defmethod visit :vector [_ _ children _]
  (apply vector :vector children))

(defmethod visit :map [_ _ children _]
  (let [childs (map (fn [[k p c]]
                      (if (nil? p)
                        [k c]
                        [k (d/without-qualified p) c]))
                    children)
        props  nil #_(m/properties schema)

        params (cond->> childs
                 (some? props)
                 (cons props))]

    (apply vector :map params)))

(defmethod visit :multi [_ schema children options]
  (let [props (m/properties schema)]
    (if (::simplified props)
      [:multi (-> props
                  (dissoc ::simplified)
                  (assoc :options (into #{} (map first children))))]
      (m/form schema options))))

(defmethod visit :merge [_ _ children _]
  (apply vector :merge children))

(defmethod visit :schema [_ schema children options]
  (visit ::m/schema schema children options))

(defmethod visit ::m/val [_ _ children _]
  (last children))

(defmethod visit ::m/schema [_ schema _ options]
  (let [schema' (m/deref schema)]
    (describe* schema' (update options ::level inc))))

(defn describe* [s options]
  (letfn [(walk-fn [schema _ children options]
            (visit (m/type schema) schema  children options))]
    (m/walk s walk-fn options)))

(defn describe
  "Given a schema, returns a string explaiaing the required shape in English"
  ([s]
   (describe s nil))
  ([s options]
   (let [s       (sm/schema s)
         s       (cond-> s
                   (= (m/type s) ::m/schema)
                   (m/deref))
         options (assoc options ::m/walk-entry-vals true ::level 0)]
     (describe* s options))))
