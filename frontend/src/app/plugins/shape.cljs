;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.shape
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.record :as crc]
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.svg.path.legacy-parser2 :as spp]
   [app.common.text :as txt]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.grid :as ctg]
   [app.common.types.shape :as cts]
   [app.common.types.shape.blur :as ctsb]
   [app.common.types.shape.export :as ctse]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.shape.path :as ctsp]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.shape.shadow :as ctss]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.guides :as dwgu]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.flex :as flex]
   [app.plugins.format :as format]
   [app.plugins.grid :as grid]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.ruler-guides :as rg]
   [app.plugins.text :as text]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [app.util.path.format :as upf]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(declare shape-proxy)
(declare shape-proxy?)

(deftype InteractionProxy [$plugin $file $page $shape $index]
  Object
  (remove [_]
    (st/emit! (dwi/remove-interaction {:id $shape} $index))))

(defn interaction-proxy? [p]
  (instance? InteractionProxy p))

(defn interaction-proxy
  [plugin-id file-id page-id shape-id index]
  (crc/add-properties!
   (InteractionProxy. plugin-id file-id page-id shape-id index)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$file" :enumerable false :get (constantly file-id)}
   {:name "$page" :enumerable false :get (constantly page-id)}
   {:name "$shape" :enumerable false :get (constantly shape-id)}
   {:name "$index" :enumerable false :get (constantly index)}

   ;; Not enumerable so we don't have an infinite loop
   {:name "shape" :enumerable false
    :get (fn [_] (shape-proxy plugin-id file-id page-id shape-id))}

   {:name "trigger"
    :get #(-> % u/proxy->interaction :event-type format/format-key)
    :set
    (fn [_ value]
      (let [value (parser/parse-keyword value)]
        (cond
          (not (contains? ctsi/event-types value))
          (u/display-not-valid :trigger value)

          :else
          (st/emit! (dwi/update-interaction
                     {:id shape-id}
                     index
                     #(assoc % :event-type value)
                     {:page-id page-id})))))}

   {:name "delay"
    :get #(-> % u/proxy->interaction :delay)
    :set
    (fn [_ value]
      (cond
        (or (not (number? value)) (not (pos? value)))
        (u/display-not-valid :delay value)

        :else
        (st/emit! (dwi/update-interaction
                   {:id shape-id}
                   index
                   #(assoc % :delay value)
                   {:page-id page-id}))))}

   {:name "action"
    :get #(-> % u/proxy->interaction (format/format-action plugin-id file-id page-id))
    :set
    (fn [self value]
      (let [params (parser/parse-action value)
            interaction
            (-> (u/proxy->interaction self)
                (d/patch-object params))]
        (cond
          (not (sm/validate ::ctsi/interaction interaction))
          (u/display-not-valid :action interaction)

          :else
          (st/emit! (dwi/update-interaction
                     {:id shape-id}
                     index
                     #(d/patch-object % params)
                     {:page-id page-id})))))}))

(def lib-typography-proxy? nil)
(def lib-component-proxy nil)

(defn text-props
  [shape]
  (d/merge
   (dwt/current-root-values {:shape shape :attrs txt/root-attrs})
   (dwt/current-paragraph-values {:shape shape :attrs txt/paragraph-attrs})
   (dwt/current-text-values {:shape shape :attrs txt/text-node-attrs})))


(defn- shadow-defaults
  [shadow]
  (d/patch-object
   {:id (uuid/next)
    :style :drop-shadow
    :color {:color clr/black :opacity 0.2}
    :offset-x 4
    :offset-y 4
    :blur 4
    :spread 0
    :hidden false}
   shadow))

(defn- blur-defaults
  [blur]
  (d/patch-object
   {:id (uuid/next)
    :type :layer-blur
    :value 4
    :hidden false}
   blur))

(deftype ShapeProxy [$plugin $file $page $id]
  Object
  (resize
    [_ width height]
    (cond
      (or (not (us/safe-number? width)) (<= width 0))
      (u/display-not-valid :resize width)

      (or (not (us/safe-number? height)) (<= height 0))
      (u/display-not-valid :resize height)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :resize "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dw/update-dimensions [$id] :width width)
                (dw/update-dimensions [$id] :height height))))

  (rotate
    [self angle center]
    (let [center (when center {:x (obj/get center "x") :y (obj/get center "y")})]
      (cond
        (not (number? angle))
        (u/display-not-valid :rotate-angle angle)

        (and (some? center) (or (not (number? (:x center))) (not (number? (:y center)))))
        (u/display-not-valid :rotate-center center)

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :rotate "Plugin doesn't have 'content:write' permission")

        :else
        (let [id (obj/get self "$id")]
          (st/emit! (dw/increase-rotation [id] angle {:center center :delta? true}))))))

  (clone
    [_]
    (let [ret-v (atom nil)]
      (cond
        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :clone "Plugin doesn't have 'content:write' permission")

        :else
        (do (st/emit! (dws/duplicate-shapes #{$id} :change-selection? false :return-ref ret-v))
            (shape-proxy $plugin (deref ret-v))))))

  (remove
    [_]
    (cond
      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :remove "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dwsh/delete-shapes #{$id}))))

  ;; Plugin data
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :getPluginData key)

      :else
      (let [shape (u/proxy->shape self)]
        (dm/get-in shape [:plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not (string? key))
      (u/display-not-valid :setPluginData-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :setPluginData-value value)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :setPluginData "Plugin doesn't have 'content:write' permission")

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
      (u/display-not-valid :getSharedPluginData-namespace namespace)

      (not (string? key))
      (u/display-not-valid :getSharedPluginData-key key)

      :else
      (let [shape (u/proxy->shape self)]
        (dm/get-in shape [:plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]

    (cond
      (not (string? namespace))
      (u/display-not-valid :setSharedPluginData-namespace namespace)

      (not (string? key))
      (u/display-not-valid :setSharedPluginData-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :setSharedPluginData-value value)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :setSharedPluginData "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dw/set-plugin-data $file :shape $id $page (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :getSharedPluginDataKeys namespace)

      :else
      (let [shape (u/proxy->shape self)]
        (apply array (keys (dm/get-in shape [:plugin-data (keyword "shared" namespace)]))))))

  ;; Only for frames + groups + booleans
  (getChildren
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (cond

        (and (not (cfh/frame-shape? shape))
             (not (cfh/group-shape? shape))
             (not (cfh/svg-raw-shape? shape))
             (not (cfh/bool-shape? shape)))
        (u/display-not-valid :getChildren (:type shape))

        :else
        (->> (u/locate-shape $file $page $id)
             (:shapes)
             (format/format-array #(shape-proxy $plugin $file $page %))))))

  (appendChild
    [_ child]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (and (not (cfh/frame-shape? shape))
             (not (cfh/group-shape? shape))
             (not (cfh/svg-raw-shape? shape))
             (not (cfh/bool-shape? shape)))
        (u/display-not-valid :appendChild (:type shape))

        (not (shape-proxy? child))
        (u/display-not-valid :appendChild-child child)

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :appendChild "Plugin doesn't have 'content:write' permission")

        :else
        (let [child-id (obj/get child "$id")]
          (st/emit! (dw/relocate-shapes #{child-id} $id 0))))))

  (insertChild
    [_ index child]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (and (not (cfh/frame-shape? shape))
             (not (cfh/group-shape? shape))
             (not (cfh/svg-raw-shape? shape))
             (not (cfh/bool-shape? shape)))
        (u/display-not-valid :insertChild (:type shape))

        (not (shape-proxy? child))
        (u/display-not-valid :insertChild-child child)

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :insertChild "Plugin doesn't have 'content:write' permission")

        :else
        (let [child-id (obj/get child "$id")]
          (st/emit! (dw/relocate-shapes #{child-id} $id index))))))

  ;; Only for frames
  (addFlexLayout
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (not (cfh/frame-shape? shape))
        (u/display-not-valid :addFlexLayout (:type shape))

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :addFlexLayout "Plugin doesn't have 'content:write' permission")

        :else
        (do (st/emit! (dwsl/create-layout-from-id $id :flex :from-frame? true :calculate-params? false))
            (grid/grid-layout-proxy $plugin $file $page $id)))))

  (addGridLayout
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (not (cfh/frame-shape? shape))
        (u/display-not-valid :addGridLayout (:type shape))

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :addGridLayout "Plugin doesn't have 'content:write' permission")

        :else
        (do (st/emit! (dwsl/create-layout-from-id $id :grid :from-frame? true :calculate-params? false))
            (grid/grid-layout-proxy $plugin $file $page $id)))))

  ;; Make masks for groups
  (makeMask
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (not (cfh/group-shape? shape))
        (u/display-not-valid :makeMask (:type shape))

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :makeMask "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dwg/mask-group #{$id})))))

  (removeMask
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (not (cfh/mask-shape? shape))
        (u/display-not-valid :removeMask (:type shape))

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :removeMask "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dwg/unmask-group #{$id})))))

  ;; Only for path and bool shapes
  (toD
    [_]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (not (cfh/path-shape? shape))
        (u/display-not-valid :makeMask (:type shape))

        :else
        (upf/format-path (:content shape)))))

  ;; Text shapes
  (getRange
    [_ start end]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (not (cfh/text-shape? shape))
        (u/display-not-valid :getRange-shape "shape is not text")

        (or (not (us/safe-int? start)) (< start 0) (> start end))
        (u/display-not-valid :getRange-start start)

        (not (us/safe-int? end))
        (u/display-not-valid :getRange-end end)

        :else
        (text/text-range $plugin $file $page $id start end))))

  (applyTypography
    [_ typography]
    (let [shape (u/locate-shape $file $page $id)]
      (cond
        (not (lib-typography-proxy? typography))
        (u/display-not-valid :applyTypography-typography typography)

        (not (cfh/text-shape? shape))
        (u/display-not-valid :applyTypography-shape (:type shape))

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :applyTypography "Plugin doesn't have 'content:write' permission")

        :else
        (let [typography (u/proxy->library-typography typography)]
          (st/emit! (dwt/apply-typography #{$id} typography $file))))))

  ;; COMPONENTS
  (isComponentInstance
    [self]
    (let [shape (u/proxy->shape self)
          file-id (obj/get self "$file")
          page-id (obj/get self "$page")
          objects (u/locate-objects file-id page-id)]
      (ctn/in-any-component? objects shape)))

  (isComponentMainInstance
    [self]
    (let [shape (u/proxy->shape self)
          file-id (obj/get self "$file")
          page-id (obj/get self "$page")
          objects (u/locate-objects file-id page-id)]
      (ctn/inside-component-main? objects shape)))

  (isComponentCopyInstance
    [self]
    (let [shape (u/proxy->shape self)]
      (ctk/in-component-copy? shape)))

  (isComponentRoot
    [self]
    (let [shape (u/proxy->shape self)]
      (ctk/instance-root? shape)))

  (isComponentHead
    [self]
    (let [shape (u/proxy->shape self)]
      (ctk/instance-head? shape)))

  (componentRefShape
    [self]
    (let [objects (u/locate-objects $file $page)
          shape (u/proxy->shape self)]
      (when (ctn/in-any-component? objects shape)
        (let [plugin-id (obj/get self "$plugin")
              [root component] (u/locate-component objects shape)
              component-page-id (:main-instance-page component)
              component-file (u/locate-file (:component-file root))
              ref-shape (ctf/get-ref-shape (:data component-file) component shape)]
          (when (and (not (:deleted component)) (some? ref-shape) (some? component-file))
            (shape-proxy plugin-id (:id component-file) component-page-id (:id ref-shape)))))))

  (componentRoot
    [self]
    (let [objects (u/locate-objects $file $page)
          shape (u/proxy->shape self)]
      (when (ctn/in-any-component? objects shape)
        (let [[root component] (u/locate-component objects shape)]
          (shape-proxy $plugin (:component-file root) (:main-instance-page component) (:id root))))))

  (componentHead
    [self]
    (let [objects (u/locate-objects $file $page)
          shape (u/proxy->shape self)]
      (when (ctn/in-any-component? objects shape)
        (let [head (ctn/get-head-shape (u/locate-objects $file $page) shape)]
          (shape-proxy $plugin $file $page (:id head))))))

  (component
    [self]
    (let [objects (u/locate-objects $file $page)
          shape (u/proxy->shape self)]
      (when (ctn/in-any-component? objects shape)
        (let [[root component] (u/locate-component objects shape)]
          (lib-component-proxy $plugin (:component-file root) (:id component))))))

  (detach
    [_]
    (st/emit! (dwl/detach-component $id)))

  ;; Export
  (export
    [self value]
    (let [value (parser/parse-export value)]
      (cond
        (not (sm/validate ::ctse/export value))
        (u/display-not-valid :export value)

        :else
        (let [payload
              {:cmd :export-shapes
               :profile-id (:profile-id @st/state)
               :wait true
               :exports [{:file-id   $file
                          :page-id   $page
                          :object-id $id
                          :name      (obj/get self "name")
                          :type      (:type value :png)
                          :suffix    (:suffix value "")
                          :scale     (:scale value 1)}]}]
          (p/create
           (fn [resolve reject]
             (->> (rp/cmd! :export payload)
                  (rx/mapcat #(rp/cmd! :export {:cmd :get-resource :wait true :id (:id %) :blob? true}))
                  (rx/mapcat #(.arrayBuffer %))
                  (rx/map #(js/Uint8Array. %))
                  (rx/subs! resolve reject))))))))

  ;; Interactions
  (addInteraction
    [self trigger action delay]
    (let [interaction
          (-> ctsi/default-interaction
              (d/patch-object (parser/parse-interaction trigger action delay)))]
      (cond
        (not (sm/validate ::ctsi/interaction interaction))
        (u/display-not-valid :addInteraction interaction)

        :else
        (let [index (-> (u/proxy->shape self) (:interactions [])  count)]
          (st/emit! (dwi/add-interaction $page $id interaction))
          (interaction-proxy $plugin $file $page $id index)))))

  (removeInteraction
    [_ interaction]
    (cond
      (not (interaction-proxy? interaction))
      (u/display-not-valid :removeInteraction interaction)

      :else
      (st/emit! (dwi/remove-interaction {:id $id} (obj/get interaction "$index")))))

  ;; Ruler guides
  (addRulerGuide
    [self orientation value]
    (let [shape (u/proxy->shape self)]
      (cond
        (not (us/safe-number? value))
        (u/display-not-valid :addRulerGuide "Value not a safe number")

        (not (contains? #{"vertical" "horizontal"} orientation))
        (u/display-not-valid :addRulerGuide "Orientation should be either 'vertical' or 'horizontal'")

        (not (cfh/frame-shape? shape))
        (u/display-not-valid :addRulerGuide "The shape is not a board")

        (not (r/check-permission $plugin "content:write"))
        (u/display-not-valid :addRulerGuide "Plugin doesn't have 'content:write' permission")

        :else
        (let [id        (uuid/next)
              axis      (parser/orientation->axis orientation)
              objects   (u/locate-objects $file $page)
              frame     (get objects $id)
              board-pos (get frame axis)
              position  (+ board-pos value)]
          (st/emit!
           (dwgu/update-guides
            {:id       id
             :axis     axis
             :position position
             :frame-id $id}))
          (rg/ruler-guide-proxy $plugin $file $page id)))))

  (removeRulerGuide
    [_ value]
    (cond
      (not (rg/ruler-guide-proxy? value))
      (u/display-not-valid :removeRulerGuide "Guide not provided")

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :removeRulerGuide "Plugin doesn't have 'content:write' permission")

      :else
      (let [guide (u/proxy->ruler-guide value)]
        (st/emit! (dwgu/remove-guide guide))))))

(defn shape-proxy? [p]
  (instance? ShapeProxy p))

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
           :get #(-> % u/proxy->shape :type format/shape-type)}

          {:name "name"
           :get #(-> % u/proxy->shape :name)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   value  (when (string? value) (-> value str/trim cfh/clean-path))
                   valid? (and (some? value)
                               (not (str/ends-with? value "/"))
                               (not (str/blank? value)))]
               (cond
                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :name "Plugin doesn't have 'content:write' permission")

                 (not valid?)
                 (u/display-not-valid :name value)

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :name value))))))}

          {:name "blocked"
           :get #(-> % u/proxy->shape :blocked boolean)
           :set
           (fn [self value]
             (cond
               (not (boolean? value))
               (u/display-not-valid :blocked value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :blocked "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")]
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :blocked value))))))}

          {:name "hidden"
           :get #(-> % u/proxy->shape :hidden boolean)
           :set
           (fn [self value]
             (cond
               (not (boolean? value))
               (u/display-not-valid :hidden value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :hidden "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")]
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :hidden value))))))}

          {:name "visible"
           :get #(-> % u/proxy->shape :hidden boolean not)
           :set
           (fn [self value]
             (cond
               (not (boolean? value))
               (u/display-not-valid :visible value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :visible "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")]
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :hidden (not value)))))))}

          {:name "proportionLock"
           :get #(-> % u/proxy->shape :proportion-lock boolean)
           :set
           (fn [self value]
             (cond
               (not (boolean? value))
               (u/display-not-valid :proportionLock value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :proportionLock "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")]
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :proportion-lock value))))))}

          {:name "constraintsHorizontal"
           :get #(-> % u/proxy->shape :constraints-h d/name)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   value (keyword value)]
               (cond
                 (not (contains? cts/horizontal-constraint-types value))
                 (u/display-not-valid :constraintsHorizontal value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :constraintsHorizontal "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :constraints-h value))))))}

          {:name "constraintsVertical"
           :get #(-> % u/proxy->shape :constraints-v d/name)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   value (keyword value)]
               (cond
                 (not (contains? cts/vertical-constraint-types value))
                 (u/display-not-valid :constraintsVertical value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :constraintsVertical "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :constraints-v value))))))}

          {:name "borderRadius"
           :get #(-> % u/proxy->shape :rx)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   shape (u/proxy->shape self)]
               (cond
                 (or (not (us/safe-int? value)) (< value 0))
                 (u/display-not-valid :borderRadius value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :borderRadius "Plugin doesn't have 'content:write' permission")

                 (or (not (ctsr/has-radius? shape)) (ctsr/radius-4? shape))
                 (st/emit! (dwsh/update-shapes [id] #(-> %
                                                         ctsr/switch-to-radius-1
                                                         (ctsr/set-radius-1 value))))

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-1 % value))))))}

          {:name "borderRadiusTopLeft"
           :get #(-> % u/proxy->shape :r1)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   shape (u/proxy->shape self)]
               (cond
                 (not (us/safe-int? value))
                 (u/display-not-valid :borderRadiusTopLeft value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :borderRadiusTopLeft "Plugin doesn't have 'content:write' permission")

                 (or (not (ctsr/has-radius? shape)) (not (ctsr/radius-4? shape)))
                 (st/emit! (dwsh/update-shapes [id] #(-> %
                                                         (ctsr/switch-to-radius-4)
                                                         (ctsr/set-radius-4 :r1 value))))

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-4 % :r1 value))))))}

          {:name "borderRadiusTopRight"
           :get #(-> % u/proxy->shape :r2)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   shape (u/proxy->shape self)]
               (cond
                 (not (us/safe-int? value))
                 (u/display-not-valid :borderRadiusTopRight value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :borderRadiusTopRight "Plugin doesn't have 'content:write' permission")

                 (or (not (ctsr/has-radius? shape)) (not (ctsr/radius-4? shape)))
                 (st/emit! (dwsh/update-shapes [id] #(-> %
                                                         (ctsr/switch-to-radius-4)
                                                         (ctsr/set-radius-4 :r2 value))))

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-4 % :r2 value))))))}

          {:name "borderRadiusBottomRight"
           :get #(-> % u/proxy->shape :r3)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   shape (u/proxy->shape self)]
               (cond
                 (not (us/safe-int? value))
                 (u/display-not-valid :borderRadiusBottomRight value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :borderRadiusBottomRight "Plugin doesn't have 'content:write' permission")

                 (or (not (ctsr/has-radius? shape)) (not (ctsr/radius-4? shape)))
                 (st/emit! (dwsh/update-shapes [id] #(-> %
                                                         (ctsr/switch-to-radius-4)
                                                         (ctsr/set-radius-4 :r3 value))))

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-4 % :r3 value))))))}

          {:name "borderRadiusBottomLeft"
           :get #(-> % u/proxy->shape :r4)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   shape (u/proxy->shape self)]
               (cond
                 (not (us/safe-int? value))
                 (u/display-not-valid :borderRadiusBottomLeft value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :borderRadiusBottomLeft "Plugin doesn't have 'content:write' permission")

                 (or (not (ctsr/has-radius? shape)) (not (ctsr/radius-4? shape)))
                 (st/emit! (dwsh/update-shapes [id] #(-> %
                                                         (ctsr/switch-to-radius-4)
                                                         (ctsr/set-radius-4 :r4 value))))

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(ctsr/set-radius-4 % :r4 value))))))}

          {:name "opacity"
           :get #(-> % u/proxy->shape :opacity)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")]
               (cond
                 (or (not (us/safe-number? value)) (< value 0) (> value 1))
                 (u/display-not-valid :opacity value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :opacity "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :opacity value))))))}

          {:name "blendMode"
           :get #(-> % u/proxy->shape :blend-mode (d/nilv :normal) d/name)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   value (keyword value)]
               (cond
                 (not (contains? cts/blend-modes value))
                 (u/display-not-valid :blendMode value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :blendMode "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :blend-mode value))))))}

          {:name "shadows"
           :get #(-> % u/proxy->shape :shadow format/format-shadows)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   value (mapv #(shadow-defaults (parser/parse-shadow %)) value)]
               (cond
                 (not (sm/validate [:vector ::ctss/shadow] value))
                 (u/display-not-valid :shadows value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :shadows "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :shadow value))))))}

          {:name "blur"
           :get #(-> % u/proxy->shape :blur format/format-blur)
           :set
           (fn [self value]
             (if (nil? value)
               (st/emit! (dwsh/update-shapes [id] #(dissoc % :blur)))
               (let [id (obj/get self "$id")
                     value (blur-defaults (parser/parse-blur value))]
                 (cond
                   (not (sm/validate ::ctsb/blur value))
                   (u/display-not-valid :blur value)

                   (not (r/check-permission plugin-id "content:write"))
                   (u/display-not-valid :blur "Plugin doesn't have 'content:write' permission")

                   :else
                   (st/emit! (dwsh/update-shapes [id] #(assoc % :blur value)))))))}

          {:name "exports"
           :get #(-> % u/proxy->shape :exports format/format-exports)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   value (parser/parse-exports value)]
               (cond
                 (not (sm/validate [:vector ::ctse/export] value))
                 (u/display-not-valid :exports value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :exports "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :exports value))))))}

          ;; Geometry properties
          {:name "x"
           :get #(-> % u/proxy->shape :x)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")]
               (cond
                 (not (us/safe-number? value))
                 (u/display-not-valid :x value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :x "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dw/update-position id {:x value})))))}

          {:name "y"
           :get #(-> % u/proxy->shape :y)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")]
               (cond
                 (not (us/safe-number? value))
                 (u/display-not-valid :y value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :y "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dw/update-position id {:y value})))))}

          {:name "parent"
           ;; not enumerable so there are no infinite loops
           :enumerable false
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        parent-id (:parent-id shape)]
                    (shape-proxy plugin-id (obj/get self "$file") (obj/get self "$page") parent-id)))}
          {:name "parentX"
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        parent-id (:parent-id shape)
                        parent (u/locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)]
                    (- (:x shape) (:x parent))))
           :set
           (fn [self value]
             (cond
               (not (us/safe-number? value))
               (u/display-not-valid :parentX value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :parentX "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")
                     parent-id (-> self u/proxy->shape :parent-id)
                     parent (u/locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                     parent-x (:x parent)]
                 (st/emit! (dw/update-position id {:x (+ parent-x value)})))))}

          {:name "parentY"
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        parent-id (:parent-id shape)
                        parent (u/locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                        parent-y (:y parent)]
                    (- (:y shape) parent-y)))
           :set
           (fn [self value]
             (cond
               (not (us/safe-number? value))
               (u/display-not-valid :parentY value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :parentY "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")
                     parent-id (-> self u/proxy->shape :parent-id)
                     parent (u/locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                     parent-y (:y parent)]
                 (st/emit! (dw/update-position id {:y (+ parent-y value)})))))}

          {:name "boardX"
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        frame-id (:parent-id shape)
                        frame (u/locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                        frame-x (:x frame)]
                    (- (:x shape) frame-x)))
           :set
           (fn [self value]
             (cond
               (not (us/safe-number? value))
               (u/display-not-valid :frameX value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :frameX "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")
                     frame-id (-> self u/proxy->shape :frame-id)
                     frame (u/locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                     frame-x (:x frame)]
                 (st/emit! (dw/update-position id {:x (+ frame-x value)})))))}

          {:name "boardY"
           :get (fn [self]
                  (let [shape (u/proxy->shape self)
                        frame-id (:parent-id shape)
                        frame (u/locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                        frame-y (:y frame)]
                    (- (:y shape) frame-y)))
           :set
           (fn [self value]
             (cond
               (not (us/safe-number? value))
               (u/display-not-valid :frameY value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :frameY "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")
                     frame-id (-> self u/proxy->shape :frame-id)
                     frame (u/locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                     frame-y (:y frame)]
                 (st/emit! (dw/update-position id {:y (+ frame-y value)})))))}

          {:name "width"
           :get #(-> % u/proxy->shape :width)}

          {:name "height"
           :get #(-> % u/proxy->shape :height)}

          {:name "bounds"
           :get #(-> % u/proxy->shape :points grc/points->rect format/format-bounds)}

          {:name "center"
           :get #(-> % u/proxy->shape gsh/shape->center format/format-point)}

          {:name "rotation"
           :get #(-> % u/proxy->shape :rotation)
           :set
           (fn [self value]
             (cond
               (not (number? value))
               (u/display-not-valid :rotation value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :rotation "Plugin doesn't have 'content:write' permission")

               :else
               (let [shape (u/proxy->shape self)]
                 (st/emit! (dw/increase-rotation #{(:id shape)} value)))))}

          {:name "flipX"
           :get #(-> % u/proxy->shape :flip-x boolean)
           :set
           (fn [self value]
             (cond
               (not (boolean? value))
               (u/display-not-valid :flipX value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :flipX "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")]
                 (st/emit! (dw/flip-horizontal-selected #{id})))))}

          {:name "flipY"
           :get #(-> % u/proxy->shape :flip-y boolean)
           :set
           (fn [self value]
             (cond
               (not (boolean? value))
               (u/display-not-valid :flipY value)

               (not (r/check-permission plugin-id "content:write"))
               (u/display-not-valid :flipY "Plugin doesn't have 'content:write' permission")

               :else
               (let [id (obj/get self "$id")]
                 (st/emit! (dw/flip-vertical-selected #{id})))))}

          ;; Strokes and fills
          {:name "fills"
           :get #(if (cfh/text-shape? data)
                   (-> % u/proxy->shape text-props :fills format/format-fills)
                   (-> % u/proxy->shape :fills format/format-fills))
           :set
           (fn [self value]
             (let [shape (u/proxy->shape self)
                   id    (:id shape)
                   value (parser/parse-fills value)]
               (cond
                 (not (sm/validate [:vector ::cts/fill] value))
                 (u/display-not-valid :fills value)

                 (cfh/text-shape? shape)
                 (st/emit! (dwt/update-attrs id {:fills value}))

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :fills "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :fills value))))))}

          {:name "strokes"
           :get #(-> % u/proxy->shape :strokes format/format-strokes)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   value (parser/parse-strokes value)]
               (cond
                 (not (sm/validate [:vector ::cts/stroke] value))
                 (u/display-not-valid :strokes value)

                 (not (r/check-permission plugin-id "content:write"))
                 (u/display-not-valid :strokes "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwsh/update-shapes [id] #(assoc % :strokes value))))))}

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
                 (grid/layout-cell-proxy plugin-id file-id page-id id))))}


          ;; Interactions
          {:name "interactions"
           :get
           (fn [self]
             (let [interactions (-> self u/proxy->shape :interactions)]
               (format/format-array
                #(interaction-proxy plugin-id file-id page-id id %)
                (range 0 (count interactions)))))})

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
                 :get #(-> % u/proxy->shape :grids format/format-frame-guides)
                 :set (fn [self value]
                        (let [id (obj/get self "$id")
                              value (parser/parse-frame-guides value)]
                          (cond
                            (not (sm/validate [:vector ::ctg/grid] value))
                            (u/display-not-valid :guides value)

                            (not (r/check-permission plugin-id "content:write"))
                            (u/display-not-valid :guides "Plugin doesn't have 'content:write' permission")

                            :else
                            (st/emit! (dwsh/update-shapes [id] #(assoc % :grids value))))))}

                {:name "rulerGuides"
                 :get
                 (fn [_]
                   (let [guides (-> (u/locate-page file-id page-id) :guides)]
                     (->> guides
                          (vals)
                          (filter #(= id (:frame-id %)))
                          (format/format-array #(rg/ruler-guide-proxy plugin-id file-id page-id (:id %))))))}

                {:name "horizontalSizing"
                 :get #(-> % u/proxy->shape :layout-item-h-sizing (d/nilv :fix) d/name)
                 :set
                 (fn [self value]
                   (let [id (obj/get self "$id")
                         value (keyword value)]
                     (cond
                       (not (contains? #{:fix :auto} value))
                       (u/display-not-valid :horizontalSizing value)

                       (not (r/check-permission plugin-id "content:write"))
                       (u/display-not-valid :horizontalSizing "Plugin doesn't have 'content:write' permission")

                       :else
                       (st/emit! (dwsl/update-layout #{id} {:layout-item-h-sizing value})))))}

                {:name "verticalSizing"
                 :get #(-> % u/proxy->shape :layout-item-v-sizing (d/nilv :fix) d/name)
                 :set
                 (fn [self value]
                   (let [id (obj/get self "$id")
                         value (keyword value)]
                     (cond
                       (not (contains? #{:fix :auto} value))
                       (u/display-not-valid :verticalSizing value)

                       (not (r/check-permission plugin-id "content:write"))
                       (u/display-not-valid :verticalSizing "Plugin doesn't have 'content:write' permission")

                       :else
                       (st/emit! (dwsl/update-layout #{id} {:layout-item-v-sizing value})))))})))

         (cond-> (cfh/text-shape? data) (text/add-text-props plugin-id))

         (cond-> (or (cfh/path-shape? data) (cfh/bool-shape? data))
           (crc/add-properties!
            {:name "content"
             :get #(-> % u/proxy->shape :content format/format-path-content)
             :set
             (fn [_ value]
               (let [content
                     (->> (parser/parse-path-content value)
                          (spp/simplify-commands))]
                 (cond
                   (not (cfh/path-shape? data))
                   (u/display-not-valid :content-type type)

                   (not (sm/validate ::ctsp/content content))
                   (u/display-not-valid :content value)

                   (not (r/check-permission plugin-id "content:write"))
                   (u/display-not-valid :content "Plugin doesn't have 'content:write' permission")

                   :else
                   (let [selrect  (gsh/content->selrect content)
                         points   (grc/rect->points selrect)]
                     (st/emit! (dwsh/update-shapes [id] (fn [shape] (assoc shape :content content :selrect selrect :points points))))))))}))))))
