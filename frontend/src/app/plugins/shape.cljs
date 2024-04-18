;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.shape
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.main.data.workspace.changes :as dwc]
   [app.main.store :as st]
   [app.plugins.utils :refer [get-data get-data-fn]]
   [cuerdas.core :as str]))

(declare data->shape-proxy)

(defn- make-fills
  [fills]
  ;; TODO: Transform explicitly?
  (apply array
         (->> fills
              (map #(clj->js % {:keyword-fn (fn [k] (str/camel (name k)))})))))

(defn- locate-shape
  [shape-id]
  (let [page-id (:current-page-id @st/state)]
    (dm/get-in @st/state [:workspace-data :pages-index page-id :objects shape-id])))

(deftype ShapeProxy [#_:clj-kondo/ignore _data]
  Object
  (getChildren
    [self]
    (apply array (->> (get-data self :shapes)
                      (map locate-shape)
                      (map data->shape-proxy))))

  (clone [_] (.log js/console (clj->js _data)))
  (delete [_] (.log js/console (clj->js _data)))
  (appendChild [_] (.log js/console (clj->js _data))))

(crc/define-properties!
  ShapeProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "ShapeProxy"))})

(defn data->shape-proxy
  [data]

  (crc/add-properties!
   (ShapeProxy. data)
   {:name "_data"
    :enumerable false}

   {:name "id"
    :get (get-data-fn :id str)}

   {:name "name"
    :get (get-data-fn :name)
    :set (fn [self value]
           (let [id (get-data self :id)]
             (st/emit! (dwc/update-shapes [id] #(assoc % :name value)))))}

   {:name "children"
    :get #(.getChildren ^js %)}

   {:name "fills"
    :get (get-data-fn :fills make-fills)
    ;;:set (fn [self value] (.log js/console self value))
    }))

