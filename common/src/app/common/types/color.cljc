;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.color
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as-alias oapi]
   [app.common.text :as txt]
   [app.common.types.plugins :as ctpg]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS & TYPES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def rgb-color-re
  #"^#(?:[0-9a-fA-F]{3}){1,2}$")

(defn- generate-rgb-color
  []
  (sg/fmap (fn [_]
             #?(:clj (format "#%06x" (rand-int 16rFFFFFF))
                :cljs
                (let [r (rand-int 255)
                      g (rand-int 255)
                      b (rand-int 255)]
                  (str "#"
                       (.. r (toString 16) (padStart 2 "0"))
                       (.. g (toString 16) (padStart 2 "0"))
                       (.. b (toString 16) (padStart 2 "0"))))))
           sg/any))

(defn rgb-color-string?
  [o]
  (and (string? o) (some? (re-matches rgb-color-re o))))

(def ^:private type:rgb-color
  {:type :string
   :pred rgb-color-string?
   :type-properties
   {:title "rgb-color"
    :description "RGB Color String"
    :error/message "expected a valid RGB color"
    :error/code "errors.invalid-rgb-color"
    :gen/gen (generate-rgb-color)
    ::oapi/type "integer"
    ::oapi/format "int64"}})

(def schema:image-color
  [:map {:title "ImageColor"}
   [:name {:optional true} :string]
   [:width ::sm/int]
   [:height ::sm/int]
   [:mtype {:optional true} [:maybe :string]]
   [:id ::sm/uuid]
   [:keep-aspect-ratio {:optional true} :boolean]])

(def gradient-types
  #{:linear :radial})

(def schema:gradient
  [:map {:title "Gradient"}
   [:type [::sm/one-of #{:linear :radial}]]
   [:start-x ::sm/safe-number]
   [:start-y ::sm/safe-number]
   [:end-x ::sm/safe-number]
   [:end-y ::sm/safe-number]
   [:width ::sm/safe-number]
   [:stops
    [:vector {:min 1 :gen/max 2}
     [:map {:title "GradientStop"}
      [:color ::rgb-color]
      [:opacity {:optional true} [:maybe ::sm/safe-number]]
      [:offset ::sm/safe-number]]]]])

(def schema:color-attrs
  [:map {:title "ColorAttrs"}
   [:id {:optional true} ::sm/uuid]
   [:name {:optional true} :string]
   [:path {:optional true} [:maybe :string]]
   [:value {:optional true} [:maybe :string]]
   [:color {:optional true} [:maybe ::rgb-color]]
   [:opacity {:optional true} [:maybe ::sm/safe-number]]
   [:modified-at {:optional true} ::sm/inst]
   [:ref-id {:optional true} ::sm/uuid]
   [:ref-file {:optional true} ::sm/uuid]
   [:gradient {:optional true} [:maybe schema:gradient]]
   [:image {:optional true} [:maybe schema:image-color]]
   [:plugin-data {:optional true} ::ctpg/plugin-data]])

(def schema:color
  [:and schema:color-attrs
   [::sm/contains-any {:strict true} [:color :gradient :image]]])

(def schema:recent-color
  [:and
   [:map {:title "RecentColor"}
    [:opacity {:optional true} [:maybe ::sm/safe-number]]
    [:color {:optional true} [:maybe ::rgb-color]]
    [:gradient {:optional true} [:maybe schema:gradient]]
    [:image {:optional true} [:maybe schema:image-color]]]
   [::sm/contains-any {:strict true} [:color :gradient :image]]])

(sm/register! ::rgb-color type:rgb-color)
(sm/register! ::color schema:color)
(sm/register! ::gradient schema:gradient)
(sm/register! ::image-color schema:image-color)
(sm/register! ::recent-color schema:recent-color)
(sm/register! ::color-attrs schema:color-attrs)

(def valid-color?
  (sm/lazy-validator schema:color))

(def check-color
  (sm/check-fn schema:color :hint "expected valid color struct"))

(def check-recent-color
  (sm/check-fn schema:recent-color))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- factory

(defn make-color
  [{:keys [id name path value color opacity ref-id ref-file gradient image]}]
  (-> {:id (or id (uuid/next))
       :name (or name color "Black")
       :path path
       :value value
       :color (or color "#000000")
       :opacity (or opacity 1)
       :ref-id ref-id
       :ref-file ref-file
       :gradient gradient
       :image image}
      (d/without-nils)))

;; --- fill

(defn fill->shape-color
  [fill]
  (d/without-nils
   {:color (:fill-color fill)
    :opacity (:fill-opacity fill)
    :gradient (:fill-color-gradient fill)
    :image (:fill-image fill)
    :ref-id (:fill-color-ref-id fill)
    :ref-file (:fill-color-ref-file fill)}))

(defn set-fill-color
  [shape position color opacity gradient image]
  (update-in shape [:fills position]
             (fn [fill]
               (d/without-nils (assoc fill
                                      :fill-color color
                                      :fill-opacity opacity
                                      :fill-color-gradient gradient
                                      :fill-image image)))))

(defn attach-fill-color
  [shape position ref-id ref-file]
  (-> shape
      (assoc-in [:fills position :fill-color-ref-id] ref-id)
      (assoc-in [:fills position :fill-color-ref-file] ref-file)))

(defn detach-fill-color
  [shape position]
  (-> shape
      (d/dissoc-in [:fills position :fill-color-ref-id])
      (d/dissoc-in [:fills position :fill-color-ref-file])))

;; stroke

(defn stroke->shape-color
  [stroke]
  (d/without-nils {:color (:stroke-color stroke)
                   :opacity (:stroke-opacity stroke)
                   :gradient (:stroke-color-gradient stroke)
                   :image (:stroke-image stroke)
                   :ref-id (:stroke-color-ref-id stroke)
                   :ref-file (:stroke-color-ref-file stroke)}))

(defn set-stroke-color
  [shape position color opacity gradient image]
  (update-in shape [:strokes position]
             (fn [stroke]
               (d/without-nils (assoc stroke
                                      :stroke-color color
                                      :stroke-opacity opacity
                                      :stroke-color-gradient gradient
                                      :stroke-image image)))))

(defn attach-stroke-color
  [shape position ref-id ref-file]
  (-> shape
      (assoc-in [:strokes position :stroke-color-ref-id] ref-id)
      (assoc-in [:strokes position :stroke-color-ref-file] ref-file)))

(defn detach-stroke-color
  [shape position]
  (-> shape
      (d/dissoc-in [:strokes position :stroke-color-ref-id])
      (d/dissoc-in [:strokes position :stroke-color-ref-file])))

;; shadow

(defn shadow->shape-color
  [shadow]
  (d/without-nils {:color (-> shadow :color :color)
                   :opacity (-> shadow :color :opacity)
                   :gradient (-> shadow :color :gradient)
                   :ref-id (-> shadow :color :id)
                   :ref-file (-> shadow :color :file-id)}))

(defn set-shadow-color
  [shape position color opacity gradient]
  (update-in shape [:shadow position :color]
             (fn [shadow-color]
               (d/without-nils (assoc shadow-color
                                      :color color
                                      :opacity opacity
                                      :gradient gradient)))))

(defn attach-shadow-color
  [shape position ref-id ref-file]
  (-> shape
      (assoc-in [:shadow position :color :id] ref-id)
      (assoc-in [:shadow position :color :file-id] ref-file)))

(defn detach-shadow-color
  [shape position]
  (-> shape
      (d/dissoc-in [:shadow position :color :id])
      (d/dissoc-in [:shadow position :color :file-id])))

;; grid

(defn grid->shape-color
  [grid]
  (d/without-nils {:color (-> grid :params :color :color)
                   :opacity (-> grid :params :color :opacity)
                   :gradient (-> grid :params :color :gradient)
                   :ref-id (-> grid :params :color :id)
                   :ref-file (-> grid :params :color :file-id)}))

(defn set-grid-color
  [shape position color opacity gradient]
  (update-in shape [:grids position :params :color]
             (fn [grid-color]
               (d/without-nils (assoc grid-color
                                      :color color
                                      :opacity opacity
                                      :gradient gradient)))))
(defn attach-grid-color
  [shape position ref-id ref-file]
  (-> shape
      (assoc-in [:grids position :params :color :id] ref-id)
      (assoc-in [:grids position :params :color :file-id] ref-file)))

(defn detach-grid-color
  [shape position]
  (-> shape
      (d/dissoc-in [:grids position :params :color :id])
      (d/dissoc-in [:grids position :params :color :file-id])))

;; --- Helpers for all colors in a shape

(defn get-text-node-colors
  "Get all colors used by a node of a text shape"
  [node]
  (concat (map fill->shape-color (:fills node))
          (map stroke->shape-color (:strokes node))))

(defn get-all-colors
  "Get all colors used by a shape, in any section."
  [shape]
  (concat (map fill->shape-color (:fills shape))
          (map stroke->shape-color (:strokes shape))
          (map shadow->shape-color (:shadow shape))
          (when (= (:type shape) :frame)
            (map grid->shape-color (:grids shape)))
          (when (= (:type shape) :text)
            (reduce (fn [colors node]
                      (concat colors (get-text-node-colors node)))
                    ()
                    (txt/node-seq (:content shape))))))

(defn uses-library-colors?
  "Check if the shape uses any color in the given library."
  [shape library-id]
  (let [all-colors (get-all-colors shape)]
    (some #(and (some? (:ref-id %))
                (= (:ref-file %) library-id))
          all-colors)))

(defn uses-library-color?
  "Check if the shape uses the given library color."
  [shape library-id color-id]
  (let [all-colors (get-all-colors shape)]
    (some #(and (= (:ref-id %) color-id)
                (= (:ref-file %) library-id))
          all-colors)))

(defn- process-shape-colors
  "Execute an update function on all colors of a shape."
  [shape process-fn]
  (let [process-fill (fn [shape [position fill]]
                       (process-fn shape
                                   position
                                   (fill->shape-color fill)
                                   set-fill-color
                                   attach-fill-color
                                   detach-fill-color))

        process-stroke (fn [shape [position stroke]]
                         (process-fn shape
                                     position
                                     (stroke->shape-color stroke)
                                     set-stroke-color
                                     attach-stroke-color
                                     detach-stroke-color))

        process-shadow (fn [shape [position shadow]]
                         (process-fn shape
                                     position
                                     (shadow->shape-color shadow)
                                     set-shadow-color
                                     attach-shadow-color
                                     detach-shadow-color))

        process-grid (fn [shape [position grid]]
                       (process-fn shape
                                   position
                                   (grid->shape-color grid)
                                   set-grid-color
                                   attach-grid-color
                                   detach-grid-color))

        process-text-node (fn [node]
                            (as-> node $
                              (reduce process-fill $ (d/enumerate (:fills $)))
                              (reduce process-stroke $ (d/enumerate (:strokes $)))))

        process-text (fn [shape]
                       (let [content     (:content shape)
                             new-content (txt/transform-nodes process-text-node content)]
                         (if (not= content new-content)
                           (assoc shape :content new-content)
                           shape)))]

    (as-> shape $
      (reduce process-fill $ (d/enumerate (:fills $)))
      (reduce process-stroke $ (d/enumerate (:strokes $)))
      (reduce process-shadow $ (d/enumerate (:shadow $)))
      (reduce process-grid $ (d/enumerate (:grids $)))
      (process-text $))))

(defn remap-colors
  "Change the shape so that any use of the given color now points to
  the given library."
  [shape library-id color]
  (letfn [(remap-color [shape position shape-color _ attach-fn _]
            (if (= (:ref-id shape-color) (:id color))
              (attach-fn shape
                         position
                         (:id color)
                         library-id)
              shape))]

    (process-shape-colors shape remap-color)))

(defn sync-shape-colors
  "Look for usage of any color of the given library inside the shape,
  and, in this case, copy the library color into the shape."
  [shape library-id library-colors]
  (letfn [(sync-color [shape position shape-color set-fn _ detach-fn]
            (if (= (:ref-file shape-color) library-id)
              (let [library-color (get library-colors (:ref-id shape-color))]
                (if (some? library-color)
                  (set-fn shape
                          position
                          (:color library-color)
                          (:opacity library-color)
                          (:gradient library-color)
                          (:image library-color))
                  (detach-fn shape position)))
              shape))]

    (process-shape-colors shape sync-color)))

(defn- eq-recent-color?
  [c1 c2]
  (or (= c1 c2)
      (and (some? (:color c1))
           (some? (:color c2))
           (= (:color c1) (:color c2)))))

(defn add-recent-color
  "Moves the color to the top of the list and then truncates up to 15"
  [state file-id color]
  (update state file-id (fn [colors]
                          (let [colors (d/removev (partial eq-recent-color? color) colors)
                                colors (conj colors color)]
                            (cond-> colors
                              (> (count colors) 15)
                              (subvec 1))))))

(defn stroke->color-att
  [stroke file-id shared-libs]
  (let [color-file-id      (:stroke-color-ref-file stroke)
        color-id           (:stroke-color-ref-id stroke)
        shared-libs-colors (dm/get-in shared-libs [color-file-id :data :colors])
        is-shared?         (contains? shared-libs-colors color-id)
        has-color?         (or (not (nil? (:stroke-color stroke))) (not (nil? (:stroke-color-gradient stroke))))
        attrs              (if (or is-shared? (= color-file-id file-id))
                             (d/without-nils {:color    (str/lower (:stroke-color stroke))
                                              :opacity  (:stroke-opacity stroke)
                                              :id       color-id
                                              :file-id  color-file-id
                                              :gradient (:stroke-color-gradient stroke)})
                             (d/without-nils {:color    (str/lower (:stroke-color stroke))
                                              :opacity  (:stroke-opacity stroke)
                                              :gradient (:stroke-color-gradient stroke)}))]
    (when has-color?
      {:attrs attrs
       :prop :stroke
       :shape-id (:shape-id stroke)
       :index (:index stroke)})))

(defn shadow->color-att
  [shadow file-id shared-libs]
  (let [color-file-id      (dm/get-in shadow [:color :file-id])
        color-id           (dm/get-in shadow [:color :id])
        shared-libs-colors (dm/get-in shared-libs [color-file-id :data :colors])
        is-shared?         (contains? shared-libs-colors color-id)
        attrs              (if (or is-shared? (= color-file-id file-id))
                             (d/without-nils {:color    (str/lower (dm/get-in shadow [:color :color]))
                                              :opacity  (dm/get-in shadow [:color :opacity])
                                              :id       color-id
                                              :file-id  (dm/get-in shadow [:color :file-id])
                                              :gradient (dm/get-in shadow [:color :gradient])})
                             (d/without-nils {:color    (str/lower (dm/get-in shadow [:color :color]))
                                              :opacity  (dm/get-in shadow [:color :opacity])
                                              :gradient (dm/get-in shadow [:color :gradient])}))]


    {:attrs attrs
     :prop :shadow
     :shape-id (:shape-id shadow)
     :index (:index shadow)}))

(defn text->color-att
  [fill file-id shared-libs]
  (let [color-file-id      (:fill-color-ref-file fill)
        color-id           (:fill-color-ref-id fill)
        shared-libs-colors (dm/get-in shared-libs [color-file-id :data :colors])
        is-shared?         (contains? shared-libs-colors color-id)
        attrs              (if (or is-shared? (= color-file-id file-id))
                             (d/without-nils {:color    (str/lower (:fill-color fill))
                                              :opacity  (:fill-opacity fill)
                                              :id       color-id
                                              :file-id  color-file-id
                                              :gradient (:fill-color-gradient fill)})
                             (d/without-nils {:color    (str/lower (:fill-color fill))
                                              :opacity  (:fill-opacity fill)
                                              :gradient (:fill-color-gradient fill)}))]
    {:attrs attrs
     :prop :content
     :shape-id (:shape-id fill)
     :index (:index fill)}))

(defn treat-node
  [node shape-id]
  (map-indexed #(assoc %2 :shape-id shape-id :index %1) node))

(defn extract-text-colors
  [text file-id shared-libs]
  (let [content (txt/node-seq txt/is-text-node? (:content text))
        content-filtered (map :fills content)
        indexed (mapcat #(treat-node % (:id text)) content-filtered)]
    (map #(text->color-att % file-id shared-libs) indexed)))

(defn fill->color-att
  [fill file-id shared-libs]
  (let [color-file-id      (:fill-color-ref-file fill)
        color-id           (:fill-color-ref-id fill)
        shared-libs-colors (dm/get-in shared-libs [color-file-id :data :colors])
        is-shared?         (contains? shared-libs-colors color-id)
        has-color?         (or (not (nil? (:fill-color fill))) (not (nil? (:fill-color-gradient fill))))
        attrs              (if (or is-shared? (= color-file-id file-id))
                             (d/without-nils {:color    (str/lower (:fill-color fill))
                                              :opacity  (:fill-opacity fill)
                                              :id       color-id
                                              :file-id  color-file-id
                                              :gradient (:fill-color-gradient fill)})
                             (d/without-nils {:color    (str/lower (:fill-color fill))
                                              :opacity  (:fill-opacity fill)
                                              :gradient (:fill-color-gradient fill)}))]
    (when has-color?
      {:attrs attrs
       :prop :fill
       :shape-id (:shape-id fill)
       :index (:index fill)})))

(defn extract-all-colors
  [shapes file-id shared-libs]
  (reduce
   (fn [list shape]
     (let [fill-obj   (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:fills shape))
           stroke-obj (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:strokes shape))
           shadow-obj (map-indexed #(assoc %2 :shape-id (:id shape) :index %1) (:shadow shape))]
       (if (= :text (:type shape))
         (-> list
             (into (map #(stroke->color-att % file-id shared-libs)) stroke-obj)
             (into (map #(shadow->color-att % file-id shared-libs)) shadow-obj)
             (into (extract-text-colors shape file-id shared-libs)))

         (-> list
             (into (map #(fill->color-att % file-id shared-libs))  fill-obj)
             (into (map #(stroke->color-att % file-id shared-libs)) stroke-obj)
             (into (map #(shadow->color-att % file-id shared-libs)) shadow-obj)))))
   []
   shapes))
