;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.color
  (:require
    [app.common.data :as d]
    [app.common.schema :as sm]
    [app.common.schema.openapi :as-alias oapi]
    [app.common.text :as txt]
    [app.common.types.color.generic :as-alias color-generic]
    [app.common.types.color.gradient :as-alias color-gradient]
    [app.common.types.color.gradient.stop :as-alias color-gradient-stop]
    [clojure.test.check.generators :as tgen]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def rgb-color-re
  #"^#(?:[0-9a-fA-F]{3}){1,2}$")

(defn- random-rgb-color
  []
  #?(:clj (format "#%06x" (rand-int 16rFFFFFF))
     :cljs
     (let [r (rand-int 255)
           g (rand-int 255)
           b (rand-int 255)]
       (str "#"
            (.. r (toString 16) (padStart 2 "0"))
            (.. g (toString 16) (padStart 2 "0"))
            (.. b (toString 16) (padStart 2 "0"))))))

(sm/def! ::rgb-color
  {:type ::rgb-color
   :pred #(and (string? %) (some? (re-matches rgb-color-re %)))
   :type-properties
   {:title "rgb-color"
    :description "RGB Color String"
    :error/message "expected a valid RGB color"
    :gen/gen (->> tgen/any (tgen/fmap (fn [_] (random-rgb-color))))

    ::oapi/type "integer"
    ::oapi/format "int64"}})

(sm/def! ::gradient
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

(sm/def! ::color
  [:map
   [:id {:optional true} ::sm/uuid]
   [:name {:optional true} :string]
   [:path {:optional true} [:maybe :string]]
   [:value {:optional true} [:maybe :string]]
   [:color {:optional true} [:maybe ::rgb-color]]
   [:opacity {:optional true} [:maybe ::sm/safe-number]]
   [:modified-at {:optional true} ::sm/inst]
   [:ref-id {:optional true} ::sm/uuid]
   [:ref-file {:optional true} ::sm/uuid]
   [:gradient {:optional true} [:maybe ::gradient]]])


;; FIXME: incomplete schema
(sm/def! ::recent-color
  [:and
   [:map {:title "RecentColot"}
    [:opacity {:optional true} [:maybe ::sm/safe-number]]
    [:color {:optional true} [:maybe ::rgb-color]]
    [:gradient {:optional true} [:maybe ::gradient]]]
   [::sm/contains-any {:strict true} [:color :gradient]]])

(def color?
  (sm/pred-fn ::color))

(def recent-color?
  (sm/pred-fn ::recent-color))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- fill

(defn fill->shape-color
  [fill]
  (d/without-nils
   {:color (:fill-color fill)
    :opacity (:fill-opacity fill)
    :gradient (:fill-color-gradient fill)
    :ref-id (:fill-color-ref-id fill)
    :ref-file (:fill-color-ref-file fill)}))

(defn set-fill-color
  [shape position color opacity gradient]
  (update-in shape [:fills position]
             (fn [fill]
               (d/without-nils (assoc fill
                                      :fill-color color
                                      :fill-opacity opacity
                                      :fill-color-gradient gradient)))))

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
                   :ref-id (:stroke-color-ref-id stroke)
                   :ref-file (:stroke-color-ref-file stroke)}))

(defn set-stroke-color
  [shape position color opacity gradient]
  (update-in shape [:strokes position]
             (fn [stroke]
               (d/without-nils (assoc stroke
                                      :stroke-color color
                                      :stroke-opacity opacity
                                      :stroke-color-gradient gradient)))))

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
                          (:gradient library-color))
                  (detach-fn shape position)))
              shape))]

    (process-shape-colors shape sync-color)))

