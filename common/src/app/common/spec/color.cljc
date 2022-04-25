;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.color
  (:require
    [app.common.data :as d]
    [app.common.spec :as us]
    [app.common.text :as txt]
    [clojure.spec.alpha :as s]))

;; TODO: waiting clojure 1.11 to rename this all :internal.stuff to a
;; more consistent name.

;; TODO: maybe define ::color-hex-string with proper hex color spec?

;; --- GRADIENTS

(s/def ::id uuid?)

(s/def :internal.gradient.stop/color string?)
(s/def :internal.gradient.stop/opacity ::us/safe-number)
(s/def :internal.gradient.stop/offset ::us/safe-number)

(s/def :internal.gradient/type #{:linear :radial})
(s/def :internal.gradient/start-x ::us/safe-number)
(s/def :internal.gradient/start-y ::us/safe-number)
(s/def :internal.gradient/end-x ::us/safe-number)
(s/def :internal.gradient/end-y ::us/safe-number)
(s/def :internal.gradient/width ::us/safe-number)

(s/def :internal.gradient/stop
  (s/keys :req-un [:internal.gradient.stop/color
                   :internal.gradient.stop/opacity
                   :internal.gradient.stop/offset]))

(s/def :internal.gradient/stops
  (s/coll-of :internal.gradient/stop :kind vector?))

(s/def ::gradient
  (s/keys :req-un [:internal.gradient/type
                   :internal.gradient/start-x
                   :internal.gradient/start-y
                   :internal.gradient/end-x
                   :internal.gradient/end-y
                   :internal.gradient/width
                   :internal.gradient/stops]))

;; --- COLORS

(s/def :internal.color/name string?)
(s/def :internal.color/path (s/nilable string?))
(s/def :internal.color/value (s/nilable string?))
(s/def :internal.color/color (s/nilable string?))
(s/def :internal.color/opacity (s/nilable ::us/safe-number))
(s/def :internal.color/gradient (s/nilable ::gradient))
(s/def :internal.color/ref-id uuid?)
(s/def :internal.color/ref-file uuid?)

(s/def ::shape-color
  (s/keys :req-un [:us/color
                   :internal.color/opacity]
          :opt-un [:internal.color/gradient
                   :internal.color/ref-id
                   :internal.color/ref-file]))

(s/def ::color
  (s/keys :opt-un [::id
                   :internal.color/name
                   :internal.color/path
                   :internal.color/value
                   :internal.color/color
                   :internal.color/opacity
                   :internal.color/gradient]))

(s/def ::recent-color
  (s/keys :opt-un [:internal.color/value
                   :internal.color/color
                   :internal.color/opacity
                   :internal.color/gradient]))

;; --- Helpers for color in different parts of a shape

;; fill

(defn fill->shape-color
  [fill]
  (d/without-nils {:color (:fill-color fill)
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

(defn sync-shape-colors
  "Look for usage of any color of the given library inside the shape,
  and, in this case, copy the library color into the shape."
  [shape library-id library-colors]
  (let [sync-color (fn [shape position shape-color set-fn detach-fn]
                     (if (= (:ref-file shape-color) library-id)
                       (let [library-color (get library-colors (:ref-id shape-color))]
                         (if (some? library-color)
                           (set-fn shape
                                   position
                                   (:color library-color)
                                   (:opacity library-color)
                                   (:gradient library-color))
                           (detach-fn shape position)))
                       shape))

        sync-fill (fn [shape [position fill]]
                    (sync-color shape
                                position
                                (fill->shape-color fill)
                                set-fill-color
                                detach-fill-color))

        sync-stroke (fn [shape [position stroke]]
                      (sync-color shape
                                  position
                                  (stroke->shape-color stroke)
                                  set-stroke-color
                                  detach-stroke-color))

        sync-shadow (fn [shape [position shadow]]
                      (sync-color shape
                                  position
                                  (shadow->shape-color shadow)
                                  set-shadow-color
                                  detach-shadow-color))

        sync-grid (fn [shape [position grid]]
                    (sync-color shape
                                position
                                (grid->shape-color grid)
                                set-grid-color
                                detach-grid-color))

        sync-text-node (fn [node]
                         (as-> node $
                           (reduce sync-fill $ (d/enumerate (:fills $)))
                           (reduce sync-stroke $ (d/enumerate (:strokes $)))))

        sync-text (fn [shape]
                    (let [content     (:content shape)
                          new-content (txt/transform-nodes sync-text-node content)]
                      (if (not= content new-content)
                        (assoc shape :content new-content)
                        shape)))]

    (as-> shape $
       (reduce sync-fill $ (d/enumerate (:fills $)))
       (reduce sync-stroke $ (d/enumerate (:strokes $)))
       (reduce sync-shadow $ (d/enumerate (:shadow $)))
       (reduce sync-grid $ (d/enumerate (:grids $)))
       (sync-text $))))
