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
   [app.common.spec :as us]
   [app.common.text :as txt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.plugins.grid :as grid]
   [app.plugins.utils :as utils :refer [get-data get-data-fn get-state]]
   [app.util.object :as obj]))

(declare data->shape-proxy)

(defn- array-to-js
  [value]
  (.freeze
   js/Object
   (apply array (->> value (map utils/to-js)))))

(defn- locate-shape
  [shape-id]
  (let [page-id (:current-page-id @st/state)]
    (dm/get-in @st/state [:workspace-data :pages-index page-id :objects shape-id])))

(deftype ShapeProxy [#_:clj-kondo/ignore _data]
  Object
  (resize
    [self width height]
    (let [id (get-data self :id)]
      (st/emit! (udw/update-dimensions [id] :width width)
                (udw/update-dimensions [id] :height height))))

  (clone [self]
    (let [id (get-data self :id)
          page-id (:current-page-id @st/state)
          ret-v (atom nil)]
      (st/emit! (dws/duplicate-shapes #{id} :change-selection? false :return-ref ret-v))
      (let [new-id (deref ret-v)
            shape (dm/get-in @st/state [:workspace-data :pages-index page-id :objects new-id])]
        (data->shape-proxy shape))))

  (remove [self]
    (let [id (get-data self :id)]
      (st/emit! (dwsh/delete-shapes #{id}))))

  ;; Only for frames + groups + booleans
  (getChildren
    [self]
    (apply array (->> (get-state self :shapes)
                      (map locate-shape)
                      (map data->shape-proxy))))

  (appendChild [self child]
    (let [parent-id (get-data self :id)
          child-id (uuid/uuid (obj/get child "id"))]
      (st/emit! (udw/relocate-shapes #{child-id} parent-id 0))))

  (insertChild [self index child]
    (let [parent-id (get-data self :id)
          child-id (uuid/uuid (obj/get child "id"))]
      (st/emit! (udw/relocate-shapes #{child-id} parent-id index))))

  ;; Only for frames
  (addFlexLayout [self]
    (let [id (get-data self :id)]
      (st/emit! (dwsl/create-layout-from-id id :flex :from-frame? true :calculate-params? false))))

  (addGridLayout [self]
    (let [id (get-data self :id)]
      (st/emit! (dwsl/create-layout-from-id id :grid :from-frame? true :calculate-params? false))
      (grid/grid-layout-proxy (obj/get self "_data")))))

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

       {:name "name"
        :get #(get-state % :name)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :name value)))))}

       {:name "blocked"
        :get #(get-state % :blocked boolean)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :blocked value)))))}

       {:name "hidden"
        :get #(get-state % :hidden boolean)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :hidden value)))))}

       {:name "proportionLock"
        :get #(get-state % :proportion-lock boolean)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :proportion-lock value)))))}

       {:name "constraintsHorizontal"
        :get #(get-state % :constraints-h d/name)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (keyword value)]
                 (when (contains? cts/horizontal-constraint-types value)
                   (st/emit! (dwc/update-shapes [id] #(assoc % :constraints-h value))))))}

       {:name "constraintsVertical"
        :get #(get-state % :constraints-v d/name)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (keyword value)]
                 (when (contains? cts/vertical-constraint-types value)
                   (st/emit! (dwc/update-shapes [id] #(assoc % :constraints-v value))))))}

       {:name "borderRadius"
        :get #(get-state % :rx)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (when (us/safe-int? value)
                   (st/emit! (dwc/update-shapes [id] #(assoc % :rx value :ry value))))))}

       {:name "borderRadiusTopLeft"
        :get #(get-state % :r1)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (when (us/safe-int? value)
                   (st/emit! (dwc/update-shapes [id] #(assoc % :r1 value))))))}

       {:name "borderRadiusTopRight"
        :get #(get-state % :r2)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (when (us/safe-int? value)
                   (st/emit! (dwc/update-shapes [id] #(assoc % :r2 value))))))}

       {:name "borderRadiusBottomRight"
        :get #(get-state % :r3)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (when (us/safe-int? value)
                   (st/emit! (dwc/update-shapes [id] #(assoc % :r3 value))))))}

       {:name "borderRadiusBottomLeft"
        :get #(get-state % :r4)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (when (us/safe-int? value)
                   (st/emit! (dwc/update-shapes [id] #(assoc % :r4 value))))))}

       {:name "opacity"
        :get #(get-state % :opacity)
        :set (fn [self value]
               (let [id (get-data self :id)]
                 (when (and (us/safe-number? value) (>= value 0) (<= value 1))
                   (st/emit! (dwc/update-shapes [id] #(assoc % :opacity value))))))}

       {:name "blendMode"
        :get #(get-state % :blend-mode d/name)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (keyword value)]
                 (when (contains? cts/blend-modes value)
                   (st/emit! (dwc/update-shapes [id] #(assoc % :blend-mode value))))))}

       {:name "shadows"
        :get #(get-state % :shadow array-to-js)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (mapv #(utils/from-js %) value)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :shadows value)))))}

       {:name "blur"
        :get #(get-state % :blur utils/to-js)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (utils/from-js value)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :blur value)))))}

       {:name "exports"
        :get #(get-state % :exports array-to-js)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (mapv #(utils/from-js %) value)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :exports value)))))}

       ;; Geometry properties
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

       {:name "parentX"
        :get (fn [self]
               (let [page-id (:current-page-id @st/state)
                     parent-id (get-state self :parent-id)
                     parent-x (dm/get-in @st/state [:workspace-data :pages-index page-id :objects parent-id :x])]
                 (- (get-state self :x) parent-x)))
        :set
        (fn [self value]
          (let [page-id (:current-page-id @st/state)
                id (get-data self :id)
                parent-id (get-state self :parent-id)
                parent-x (dm/get-in @st/state [:workspace-data :pages-index page-id :objects parent-id :x])]
            (st/emit! (udw/update-position id {:x (+ parent-x value)}))))}

       {:name "parentY"
        :get (fn [self]
               (let [page-id (:current-page-id @st/state)
                     parent-id (get-state self :parent-id)
                     parent-y (dm/get-in @st/state [:workspace-data :pages-index page-id :objects parent-id :y])]
                 (- (get-state self :y) parent-y)))
        :set
        (fn [self value]
          (let [page-id (:current-page-id @st/state)
                id (get-data self :id)
                parent-id (get-state self :parent-id)
                parent-y (dm/get-in @st/state [:workspace-data :pages-index page-id :objects parent-id :y])]
            (st/emit! (udw/update-position id {:y (+ parent-y value)}))))}

       {:name "frameX"
        :get (fn [self]
               (let [page-id (:current-page-id @st/state)
                     frame-id (get-state self :frame-id)
                     frame-x (dm/get-in @st/state [:workspace-data :pages-index page-id :objects frame-id :x])]
                 (- (get-state self :x) frame-x)))
        :set
        (fn [self value]
          (let [page-id (:current-page-id @st/state)
                id (get-data self :id)
                frame-id (get-state self :frame-id)
                frame-x (dm/get-in @st/state [:workspace-data :pages-index page-id :objects frame-id :x])]
            (st/emit! (udw/update-position id {:x (+ frame-x value)}))))}

       {:name "frameY"
        :get (fn [self]
               (let [page-id (:current-page-id @st/state)
                     frame-id (get-state self :frame-id)
                     frame-y (dm/get-in @st/state [:workspace-data :pages-index page-id :objects frame-id :y])]
                 (- (get-state self :y) frame-y)))
        :set
        (fn [self value]
          (let [page-id (:current-page-id @st/state)
                id (get-data self :id)
                frame-id (get-state self :frame-id)
                frame-y (dm/get-in @st/state [:workspace-data :pages-index page-id :objects frame-id :y])]
            (st/emit! (udw/update-position id {:y (+ frame-y value)}))))}

       {:name "width"
        :get #(get-state % :width)}

       {:name "height"
        :get #(get-state % :height)}

       {:name "flipX"
        :get #(get-state % :flip-x)}

       {:name "flipY"
        :get #(get-state % :flip-y)}

       ;; Strokes and fills
       {:name "fills"
        :get #(get-state % :fills array-to-js)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (mapv #(utils/from-js %) value)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :fills value)))))}

       {:name "strokes"
        :get #(get-state % :strokes array-to-js)
        :set (fn [self value]
               (let [id (get-data self :id)
                     value (mapv #(utils/from-js %) value)]
                 (st/emit! (dwc/update-shapes [id] #(assoc % :strokes value)))))})

      (cond-> (or (cfh/frame-shape? data) (cfh/group-shape? data) (cfh/svg-raw-shape? data) (cfh/bool-shape? data))
        (crc/add-properties!
         {:name "children"
          :get #(.getChildren ^js %)}))

      (cond-> (not (or (cfh/frame-shape? data) (cfh/group-shape? data) (cfh/svg-raw-shape? data) (cfh/bool-shape? data)))
        (-> (obj/unset! "appendChild")
            (obj/unset! "insertChild")
            (obj/unset! "getChildren")))

      (cond-> (cfh/frame-shape? data)
        (-> (crc/add-properties!
             {:name "grid"
              :get
              (fn [self]
                (let [layout (get-state self :layout)]
                  (when (= :grid layout)
                    (grid/grid-layout-proxy data))))}

             {:name "guides"
              :get #(get-state % :grids array-to-js)
              :set (fn [self value]
                     (let [id (get-data self :id)
                           value (mapv #(utils/from-js %) value)]
                       (st/emit! (dwc/update-shapes [id] #(assoc % :grids value)))))})

            ;; TODO: Flex properties
            #_(crc/add-properties!
               {:name "flex"
                :get
                (fn [self]
                  (let [layout (get-state self :layout)]
                    (when (= :flex layout)
                      (flex-layout-proxy data))))})))

      (cond-> (not (cfh/frame-shape? data))
        (-> (obj/unset! "addGridLayout")
            (obj/unset! "addFlexLayout")))

      (cond-> (cfh/text-shape? data)
        (-> (crc/add-properties!
             {:name "characters"
              :get #(get-state % :content txt/content->text)
              :set (fn [self value]
                     (let [id (get-data self :id)]
                       (st/emit! (dwc/update-shapes [id] #(txt/change-text % value)))))})

            (crc/add-properties!
             {:name "growType"
              :get #(get-state % :grow-type d/name)
              :set (fn [self value]
                     (let [id (get-data self :id)
                           value (keyword value)]
                       (when (contains? #{:auto-width :auto-height :fixed} value)
                         (st/emit! (dwc/update-shapes [id] #(assoc % :grow-type value))))))})))))
