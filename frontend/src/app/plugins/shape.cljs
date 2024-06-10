;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.shape
  "RPC for plugins runtime."
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.record :as crc]
   [app.common.spec :as us]
   [app.common.svg.path.legacy-parser2 :as spp]
   [app.common.text :as txt]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.shape.radius :as ctsr]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.plugins.flex :as flex]
   [app.plugins.grid :as grid]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [app.util.path.format :as upf]
   [app.util.text-editor :as ted]
   [cuerdas.core :as str]))

(declare shape-proxy)

(defn parse-command
  [entry]
  (update entry
          :command
          #(case %
             "M" :move-to
             "Z" :close-path
             "L" :line-to
             "H" :line-to-horizontal
             "V" :line-to-vertical
             "C" :curve-to
             "S" :smooth-curve-to
             "Q" :quadratic-bezier-curve-to
             "T" :smooth-quadratic-bezier-curve-to
             "A" :elliptical-arc
             (keyword %))))

(defn text-props
  [shape]
  (d/merge
   (dwt/current-root-values {:shape shape :attrs txt/root-attrs})
   (dwt/current-paragraph-values {:shape shape :attrs txt/paragraph-attrs})
   (dwt/current-text-values {:shape shape :attrs txt/text-node-attrs})))

(deftype ShapeProxy [$plugin $file $page $id]
  Object
  (resize
    [_ width height]
    (st/emit! (dw/update-dimensions [$id] :width width)
              (dw/update-dimensions [$id] :height height)))

  (clone
    [_]
    (let [ret-v (atom nil)]
      (st/emit! (dws/duplicate-shapes #{$id} :change-selection? false :return-ref ret-v))
      (shape-proxy $plugin (deref ret-v))))

  (remove
    [_]
    (st/emit! (dwsh/delete-shapes #{$id})))

  ;; Plugin data
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :shape-plugin-data-key key)

      :else
      (let [shape (u/proxy->shape self)]
        (dm/get-in shape [:plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not (string? key))
      (u/display-not-valid :shape-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :shape-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :shape $id $page (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [shape (u/proxy->shape self)]
      (apply array (keys (dm/get-in shape [:plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :shape-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :shape-plugin-data-key key)

      :else
      (let [shape (u/proxy->shape self)]
        (dm/get-in shape [:plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]

    (cond
      (not (string? namespace))
      (u/display-not-valid :shape-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :shape-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :shape-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :shape $id $page (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :shape-plugin-data-namespace namespace)

      :else
      (let [shape (u/proxy->shape self)]
        (apply array (keys (dm/get-in shape [:plugin-data (keyword "shared" namespace)]))))))

  ;; Only for frames + groups + booleans
  (getChildren
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (if (or (cfh/frame-shape? shape) (cfh/group-shape? shape) (cfh/svg-raw-shape? shape) (cfh/bool-shape? shape))
        (apply array (->> (u/locate-shape $file $page $id)
                          :shapes
                          (map #(shape-proxy $plugin $file $page %))))
        (u/display-not-valid :getChildren (:type shape)))))

  (appendChild
    [_ child]
    (let [shape (u/locate-shape $file $page $id)]
      (if (or (cfh/frame-shape? shape) (cfh/group-shape? shape) (cfh/svg-raw-shape? shape) (cfh/bool-shape? shape))
        (let [child-id (obj/get child "$id")]
          (st/emit! (dw/relocate-shapes #{child-id} $id 0)))
        (u/display-not-valid :appendChild (:type shape)))))

  (insertChild
    [_ index child]
    (let [shape (u/locate-shape $file $page $id)]
      (if (or (cfh/frame-shape? shape) (cfh/group-shape? shape) (cfh/svg-raw-shape? shape) (cfh/bool-shape? shape))
        (let [child-id (obj/get child "$id")]
          (st/emit! (dw/relocate-shapes #{child-id} $id index)))
        (u/display-not-valid :insertChild (:type shape)))))

  ;; Only for frames
  (addFlexLayout
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (if (cfh/frame-shape? shape)
        (do (st/emit! (dwsl/create-layout-from-id $id :flex :from-frame? true :calculate-params? false))
            (grid/grid-layout-proxy $plugin $file $page $id))
        (u/display-not-valid :addFlexLayout (:type shape)))))

  (addGridLayout
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (if (cfh/frame-shape? shape)
        (do (st/emit! (dwsl/create-layout-from-id $id :grid :from-frame? true :calculate-params? false))
            (grid/grid-layout-proxy $plugin $file $page $id))
        (u/display-not-valid :addGridLayout (:type shape)))))

  ;; Make masks for groups
  (makeMask
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (if (cfh/group-shape? shape)
        (st/emit! (dwg/mask-group #{$id}))
        (u/display-not-valid :makeMask (:type shape)))))

  (removeMask
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (if (cfh/mask-shape? shape)
        (st/emit! (dwg/unmask-group #{$id}))
        (u/display-not-valid :removeMask (:type shape)))))

  ;; Only for path and bool shapes
  (toD
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (if (cfh/path-shape? shape)
        (upf/format-path (:content shape))
        (u/display-not-valid :makeMask (:type shape)))))

  ;; Text shapes
  (getRange
    [_ _from _to]
    (let [shape (u/locate-shape $file $page $id)]
      (if (cfh/text-shape? shape)
        nil ;; TODO
        (u/display-not-valid :makeMask (:type shape))))))

(crc/define-properties!
  ShapeProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "ShapeProxy"))})

(defn shape-proxy
  ([plugin-id id]
   (shape-proxy plugin-id (:current-file-id @st/state) (:current-page-id @st/state) id))

  ([plugin-id page-id id]
   (shape-proxy plugin-id (:current-file-id @st/state) page-id id))

  ([plugin-id file-id page-id id]
   (assert (uuid? file-id))
   (assert (uuid? page-id))
   (assert (uuid? id))

   (let [data (u/locate-shape file-id page-id id)]
     (-> (ShapeProxy. plugin-id file-id page-id id)
         (crc/add-properties!
          {:name "$plugin" :enumerable false :get (constantly plugin-id)}
          {:name "$id" :enumerable false :get (constantly id)}
          {:name "$file" :enumerable false :get (constantly file-id)}
          {:name "$page" :enumerable false :get (constantly page-id)}

          {:name "id"
           :get #(-> % u/proxy->shape :id str)}

          {:name "type"
           :get #(-> % u/proxy->shape :type name)}

          {:name "name"
           :get #(-> % u/proxy->shape :name)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value  (when (string? value) (-> value str/trim cfh/clean-path))
                        valid? (and (some? value)
                                    (not (str/ends-with? value "/"))
                                    (not (str/blank? value)))]
                    (if valid?
                      (st/emit! (dwsh/update-shapes [id] #(assoc % :name value)))
                      (u/display-not-valid :shape-name value))))}

          {:name "blocked"
           :get #(-> % u/proxy->shape :blocked boolean)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (st/emit! (dwsh/update-shapes [id] #(assoc % :blocked value)))))}

          {:name "hidden"
           :get #(-> % u/proxy->shape :hidden boolean)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (st/emit! (dwsh/update-shapes [id] #(assoc % :hidden value)))))}

          {:name "proportionLock"
           :get #(-> % u/proxy->shape :proportion-lock boolean)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (st/emit! (dwsh/update-shapes [id] #(assoc % :proportion-lock value)))))}

          {:name "constraintsHorizontal"
           :get #(-> % u/proxy->shape :constraints-h d/name)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (keyword value)]
                    (when (contains? cts/horizontal-constraint-types value)
                      (st/emit! (dwsh/update-shapes [id] #(assoc % :constraints-h value))))))}

          {:name "constraintsVertical"
           :get #(-> % u/proxy->shape :constraints-v d/name)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (keyword value)]
                    (when (contains? cts/vertical-constraint-types value)
                      (st/emit! (dwsh/update-shapes [id] #(assoc % :constraints-v value))))))}

          {:name "borderRadius"
           :get #(-> % u/proxy->shape :rx)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        shape (u/proxy->shape self)]
                    (when (us/safe-int? value)
                      (when (or (not (ctsr/has-radius? shape)) (ctsr/radius-4? shape))
                        (st/emit! (dwsh/update-shapes [id] ctsr/switch-to-radius-1)))
                      (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-1 % value))))))}

          {:name "borderRadiusTopLeft"
           :get #(-> % u/proxy->shape :r1)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        shape (u/proxy->shape self)]
                    (when (us/safe-int? value)
                      (when (or (not (ctsr/has-radius? shape)) (not (ctsr/radius-4? shape)))
                        (st/emit! (dwsh/update-shapes [id] ctsr/switch-to-radius-4)))
                      (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-4 % :r1 value))))))}

          {:name "borderRadiusTopRight"
           :get #(-> % u/proxy->shape :r2)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        shape (u/proxy->shape self)]
                    (when (us/safe-int? value)
                      (when (or (not (ctsr/has-radius? shape)) (not (ctsr/radius-4? shape)))
                        (st/emit! (dwsh/update-shapes [id] ctsr/switch-to-radius-4)))
                      (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-4 % :r2 value))))))}

          {:name "borderRadiusBottomRight"
           :get #(-> % u/proxy->shape :r3)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        shape (u/proxy->shape self)]
                    (when (us/safe-int? value)
                      (when (or (not (ctsr/has-radius? shape)) (not (ctsr/radius-4? shape)))
                        (st/emit! (dwsh/update-shapes [id] ctsr/switch-to-radius-4)))
                      (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-4 % :r3 value))))))}

          {:name "borderRadiusBottomLeft"
           :get #(-> % u/proxy->shape :r4)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        shape (u/proxy->shape self)]
                    (when (us/safe-int? value)
                      (when (or (not (ctsr/has-radius? shape)) (not (ctsr/radius-4? shape)))
                        (st/emit! (dwsh/update-shapes [id] ctsr/switch-to-radius-4)))
                      (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-4 % :r4 value))))))}

          {:name "opacity"
           :get #(-> % u/proxy->shape :opacity)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (when (and (us/safe-number? value) (>= value 0) (<= value 1))
                      (st/emit! (dwsh/update-shapes [id] #(assoc % :opacity value))))))}

          {:name "blendMode"
           :get #(-> % u/proxy->shape :blend-mode (d/nilv :normal) d/name)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (keyword value)]
                    (when (contains? cts/blend-modes value)
                      (st/emit! (dwsh/update-shapes [id] #(assoc % :blend-mode value))))))}

          {:name "shadows"
           :get #(-> % u/proxy->shape :shadow u/array-to-js)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (mapv (fn [val]
                                      ;; Merge default shadow properties
                                      (d/patch-object
                                       {:id (uuid/next)
                                        :style :drop-shadow
                                        :color {:color clr/black :opacity 0.2}
                                        :offset-x 4
                                        :offset-y 4
                                        :blur 4
                                        :spread 0
                                        :hidden false}
                                       (u/from-js val #{:style :type})))
                                    value)]
                    (st/emit! (dwsh/update-shapes [id] #(assoc % :shadow value)))))}

          {:name "blur"
           :get #(-> % u/proxy->shape :blur u/to-js)
           :set (fn [self value]
                  (if (nil? value)
                    (st/emit! (dwsh/update-shapes [id] #(dissoc % :blur)))
                    (let [id (obj/get self "$id")
                          value
                          (d/patch-object
                           {:id (uuid/next)
                            :type :layer-blur
                            :value 4
                            :hidden false}
                           (u/from-js value))]
                      (st/emit! (dwsh/update-shapes [id] #(assoc % :blur value))))))}

          {:name "exports"
           :get #(-> % u/proxy->shape :exports u/array-to-js)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (mapv #(u/from-js %) value)]
                    (st/emit! (dwsh/update-shapes [id] #(assoc % :exports value)))))}

          ;; Geometry properties
          {:name "x"
           :get #(-> % u/proxy->shape :x)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")]
               (st/emit! (dw/update-position id {:x value}))))}

          {:name "y"
           :get #(-> % u/proxy->shape :y)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")]
               (st/emit! (dw/update-position id {:y value}))))}

          {:name "parentX"
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        parent-id (:parent-id shape)
                        parent (u/locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)]
                    (- (:x shape) (:x parent))))
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   parent-id (-> self u/proxy->shape :parent-id)
                   parent (u/locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                   parent-x (:x parent)]
               (st/emit! (dw/update-position id {:x (+ parent-x value)}))))}

          {:name "parentY"
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        parent-id (:parent-id shape)
                        parent (u/locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                        parent-y (:y parent)]
                    (- (:y shape) parent-y)))
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   parent-id (-> self u/proxy->shape :parent-id)
                   parent (u/locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                   parent-y (:y parent)]
               (st/emit! (dw/update-position id {:y (+ parent-y value)}))))}

          {:name "frameX"
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        frame-id (:parent-id shape)
                        frame (u/locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                        frame-x (:x frame)]
                    (- (:x shape) frame-x)))
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   frame-id (-> self u/proxy->shape :frame-id)
                   frame (u/locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                   frame-x (:x frame)]
               (st/emit! (dw/update-position id {:x (+ frame-x value)}))))}

          {:name "frameY"
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        frame-id (:parent-id shape)
                        frame (u/locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                        frame-y (:y frame)]
                    (- (:y shape) frame-y)))
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   frame-id (-> self u/proxy->shape :frame-id)
                   frame (u/locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                   frame-y (:y frame)]
               (st/emit! (dw/update-position id {:y (+ frame-y value)}))))}

          {:name "width"
           :get #(-> % u/proxy->shape :width)}

          {:name "height"
           :get #(-> % u/proxy->shape :height)}

          {:name "flipX"
           :get #(-> % u/proxy->shape :flip-x)}

          {:name "flipY"
           :get #(-> % u/proxy->shape :flip-y)}

          ;; Strokes and fills
          {:name "fills"
           :get #(if (cfh/text-shape? data)
                   (-> % u/proxy->shape text-props :fills u/array-to-js)
                   (-> % u/proxy->shape :fills u/array-to-js))
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (mapv #(u/from-js %) value)]
                    (st/emit! (dwsh/update-shapes [id] #(assoc % :fills value)))))}

          {:name "strokes"
           :get #(-> % u/proxy->shape :strokes u/array-to-js)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (mapv #(u/from-js % #{:stroke-style :stroke-alignment}) value)]
                    (st/emit! (dwsh/update-shapes [id] #(assoc % :strokes value)))))}

          {:name "layoutChild"
           :get
           (fn [self]
             (let [file-id (obj/get self "$file")
                   page-id (obj/get self "$page")
                   id (obj/get self "$id")
                   objects (u/locate-objects file-id page-id)]
               (when (ctl/any-layout-immediate-child-id? objects id)
                 (flex/layout-child-proxy plugin-id file-id page-id id))))}

          {:name "layoutCell"
           :get
           (fn [self]
             (let [file-id (obj/get self "$file")
                   page-id (obj/get self "$page")
                   id (obj/get self "$id")
                   objects (u/locate-objects file-id page-id)]
               (when (ctl/grid-layout-immediate-child-id? objects id)
                 (grid/layout-cell-proxy plugin-id file-id page-id id))))})

         (cond-> (or (cfh/frame-shape? data) (cfh/group-shape? data) (cfh/svg-raw-shape? data) (cfh/bool-shape? data))
           (crc/add-properties!
            {:name "children"
             :enumerable false
             :get #(.getChildren ^js %)}))

         (cond-> (cfh/frame-shape? data)
           (-> (crc/add-properties!
                {:name "grid"
                 :get
                 (fn [self]
                   (let [layout (-> self u/proxy->shape :layout)
                         file-id (obj/get self "$file")
                         page-id (obj/get self "$page")
                         id (obj/get self "$id")]
                     (when (= :grid layout)
                       (grid/grid-layout-proxy plugin-id file-id page-id id))))}

                {:name "flex"
                 :get
                 (fn [self]
                   (let [layout (-> self u/proxy->shape :layout)
                         file-id (obj/get self "$file")
                         page-id (obj/get self "$page")
                         id (obj/get self "$id")]
                     (when (= :flex layout)
                       (flex/flex-layout-proxy plugin-id file-id page-id id))))}

                {:name "guides"
                 :get #(-> % u/proxy->shape :grids u/array-to-js)
                 :set (fn [self value]
                        (let [id (obj/get self "$id")
                              value (mapv #(u/from-js %) value)]
                          (st/emit! (dwsh/update-shapes [id] #(assoc % :grids value)))))}

                {:name "horizontalSizing"
                 :get #(-> % u/proxy->shape :layout-item-h-sizing (d/nilv :fix) d/name)
                 :set
                 (fn [self value]
                   (let [id (obj/get self "$id")
                         value (keyword value)]
                     (when (contains? #{:fix :auto} value)
                       (st/emit! (dwsl/update-layout #{id} {:layout-item-h-sizing value})))))}

                {:name "verticalSizing"
                 :get #(-> % u/proxy->shape :layout-item-v-sizing (d/nilv :fix) d/name)
                 :set
                 (fn [self value]
                   (let [id (obj/get self "$id")
                         value (keyword value)]
                     (when (contains? #{:fix :auto} value)
                       (st/emit! (dwsl/update-layout #{id} {:layout-item-v-sizing value})))))})))

         (cond-> (cfh/text-shape? data)
           (crc/add-properties!
            {:name "characters"
             :get #(-> % u/proxy->shape :content txt/content->text)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 ;; The user is currently editing the text. We need to update the
                 ;; editor as well
                 (when (contains? (:workspace-editor-state @st/state) id)
                   (let [shape (u/proxy->shape self)
                         editor
                         (-> shape
                             (txt/change-text value)
                             :content
                             ted/import-content
                             ted/create-editor-state)]
                     (st/emit! (dwt/update-editor-state shape editor))))
                 (st/emit! (dwsh/update-shapes [id] #(txt/change-text % value)))))}

            {:name "growType"
             :get #(-> % u/proxy->shape :grow-type d/name)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")
                     value (keyword value)]
                 (when (contains? #{:auto-width :auto-height :fixed} value)
                   (st/emit! (dwsh/update-shapes [id] #(assoc % :grow-type value))))))}

            {:name "fontId"
             :get #(-> % u/proxy->shape text-props :font-id)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:font-id value}))))}

            {:name "fontFamily"
             :get #(-> % u/proxy->shape text-props :font-family)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:font-id value}))))}

            {:name "fontVariantId"
             :get #(-> % u/proxy->shape text-props :font-variant-id)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:font-id value}))))}

            {:name "fontSize"
             :get #(-> % u/proxy->shape text-props :font-size)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:font-id value}))))}

            {:name "fontWeight"
             :get #(-> % u/proxy->shape text-props :font-weight)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:font-id value}))))}

            {:name "fontStyle"
             :get #(-> % u/proxy->shape text-props :font-style)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:font-style value}))))}

            {:name "lineHeight"
             :get #(-> % u/proxy->shape text-props :line-height)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:line-height value}))))}

            {:name "letterSpacing"
             :get #(-> % u/proxy->shape text-props :letter-spacing)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:letter-spacing value}))))}

            {:name "textTransform"
             :get #(-> % u/proxy->shape text-props :text-transform)
             :set
             (fn [self value]
               (let [id (obj/get self "$id")]
                 (st/emit! (dwt/update-attrs id {:text-transform value}))))}))

         (cond-> (or (cfh/path-shape? data) (cfh/bool-shape? data))
           (crc/add-properties!
            {:name "content"
             :get #(-> % u/proxy->shape :content u/array-to-js)
             :set
             (fn [_ value]
               (let [content
                     (->> value
                          (map u/from-js)
                          (mapv parse-command)
                          (spp/simplify-commands))
                     selrect  (gsh/content->selrect content)
                     points   (grc/rect->points selrect)]
                 (st/emit! (dwsh/update-shapes [id] (fn [shape] (assoc shape :content content :selrect selrect :points points))))))}))))))
