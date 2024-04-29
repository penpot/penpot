;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.shape
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.record :as crc]
   [app.common.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.changes :as dwc]
   [app.main.store :as st]
   [app.plugins.utils :as utils :refer [get-data get-data-fn]]
   [app.util.object :as obj]))

(declare data->shape-proxy)

(defn- make-fills
  [fills]
  (.freeze
   js/Object
   (apply array (->> fills (map utils/to-js)))))

(defn- make-strokes
  [strokes]
  (.freeze
   js/Object
   (apply array (->> strokes (map utils/to-js)))))

(defn- locate-shape
  [shape-id]
  (let [page-id (:current-page-id @st/state)]
    (dm/get-in @st/state [:workspace-data :pages-index page-id :objects shape-id])))

(defn- get-state
  ([self attr]
   (let [id (get-data self :id)
         page-id (d/nilv (get-data self :page-id) (:current-page-id @st/state))]
     (dm/get-in @st/state [:workspace-data :pages-index page-id :objects id attr])))
  ([self attr mapfn]
   (-> (get-state self attr)
       (mapfn))))

(deftype ShapeProxy [^:mutable #_:clj-kondo/ignore _data]
  Object
  (getChildren
    [self]
    (apply array (->> (get-state self :shapes)
                      (map locate-shape)
                      (map data->shape-proxy))))

  (resize
    [self width height]
    (let [id (get-data self :id)]
      (st/emit! (udw/update-dimensions [id] :width width)
                (udw/update-dimensions [id] :height height))))

  (clone [_] (.log js/console (clj->js _data)))
  (delete [_] (.log js/console (clj->js _data)))

  (appendChild [self child]
    (let [parent-id (get-data self :id)
          child-id (uuid/uuid (obj/get child "id"))]
      (st/emit! (udw/relocate-shapes #{ child-id } parent-id 0))))

  (insertChild [self index child]
    (let [parent-id (get-data self :id)
          child-id (uuid/uuid (obj/get child "id"))]
      (st/emit! (udw/relocate-shapes #{ child-id } parent-id index)))))

(crc/define-properties!
  ShapeProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "ShapeProxy"))})

(defn data->shape-proxy
  [data]

  (-> (ShapeProxy. data)
      (crc/add-properties!
       {:name "_data"
        :enumerable false}

       {:name "id"
        :get (get-data-fn :id str)}

       {:name "type"
        :get (get-data-fn :type name)}

       {:name "x"
        :get #(get-state % :x)
        :set
        (fn [self value]
          (let [id (get-data self :id)]
            (st/emit! (udw/update-position id {:x value}))))}

       {:name "y"
        :get #(get-state % :y)
        :set
        (fn [self value]
          (let [id (get-data self :id)]
            (st/emit! (udw/update-position id {:y value}))))}

       {:name "width"
        :get #(get-state % :width)}

       {:name "height"
        :get #(get-state % :height)}

       {:name "name"
        :get #(get-state % :name)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :name value)))))}

       {:name "fills"
        :get #(get-state % :fills make-fills)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (mapv #(utils/from-js %) value)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :fills value)))))
        }

       {:name "strokes"
        :get #(get-state % :strokes make-strokes)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (mapv #(utils/from-js %) value)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :strokes value)))))
        })

      (cond-> (or (cfh/frame-shape? data) (cfh/group-shape? data) (cfh/svg-raw-shape? data) (cfh/bool-shape? data))
        (crc/add-properties!
         {:name "children"
          :get #(.getChildren ^js %)}))

      (cond-> (cfh/text-shape? data)
        (crc/add-properties!
         {:name "characters"
          :get #(get-state % :content txt/content->text)
          :set (fn [self value]
                 (let [id (get-data self :id)]
                   (st/emit! (dwc/update-shapes [id] #(txt/change-text % value)))))}))))

