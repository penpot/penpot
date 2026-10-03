;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.strokes
  (:require
   [app.common.types.stroke :as cts]
   [app.plugins.format :as format]
   [app.plugins.gradients :as gradients]
   [app.plugins.parser :as parser]
   [app.util.object :as obj]))

(defn- set-side-width!
  "Write one per-side width on the local stroke state. When the shape type does
  not support per-side widths, report a validation error instead of storing an
  inert value."
  [state attr value on-change! per-side-allowed? not-valid]
  (if (and (false? per-side-allowed?) (fn? not-valid))
    (not-valid attr value)
    (do
      (swap! state #(cts/set-width-to-single-side % attr value))
      (on-change!))))

(defn stroke-proxy
  ([stroke-data on-change!]
   (stroke-proxy stroke-data on-change! nil))
  ([stroke-data on-change! {:keys [per-side-allowed? not-valid]}]
   (let [state (atom stroke-data)]
     (obj/reify {:name "StrokeProxy"}
       ;; Raw stroke map, hidden from plugin code. `parse-stroke` reads it so a
       ;; commit preserves the attributes the plugin did not touch (notably the
       ;; per-side widths, which the public getters would otherwise materialize
       ;; through their uniform-width fallback).
       :$state {:enumerable false :get (fn [] @state)}

       :strokeColor
       {:get (fn [] (:stroke-color @state))
        :set (fn [v]
               (swap! state #(-> % (assoc :stroke-color v) (dissoc :stroke-color-gradient :stroke-image)))
               (on-change!))}

       :strokeColorRefFile
       {:get (fn [] (format/format-id (:stroke-color-ref-file @state)))
        :set (fn [v] (swap! state assoc :stroke-color-ref-file (parser/parse-id v)) (on-change!))}

       :strokeColorRefId
       {:get (fn [] (format/format-id (:stroke-color-ref-id @state)))
        :set (fn [v] (swap! state assoc :stroke-color-ref-id (parser/parse-id v)) (on-change!))}

       :strokeOpacity
       {:get (fn [] (:stroke-opacity @state))
        :set (fn [v] (swap! state assoc :stroke-opacity v) (on-change!))}

       :strokeStyle
       {:get (fn [] (format/format-key (:stroke-style @state)))
        :set (fn [v] (swap! state assoc :stroke-style (parser/parse-keyword v)) (on-change!))}

       :strokeWidth
       {:get (fn [] (:stroke-width @state))
        :set (fn [v]
               (swap! state #(cts/set-width-to-all-sides % v))
               (on-change!))}

       :strokeWidthTop
       {:get (fn [] (cts/side-width @state :stroke-width-top))
        :set (fn [v] (set-side-width! state :stroke-width-top v on-change! per-side-allowed? not-valid))}

       :strokeWidthRight
       {:get (fn [] (cts/side-width @state :stroke-width-right))
        :set (fn [v] (set-side-width! state :stroke-width-right v on-change! per-side-allowed? not-valid))}

       :strokeWidthBottom
       {:get (fn [] (cts/side-width @state :stroke-width-bottom))
        :set (fn [v] (set-side-width! state :stroke-width-bottom v on-change! per-side-allowed? not-valid))}

       :strokeWidthLeft
       {:get (fn [] (cts/side-width @state :stroke-width-left))
        :set (fn [v] (set-side-width! state :stroke-width-left v on-change! per-side-allowed? not-valid))}

       :strokeAlignment
       {:get (fn [] (format/format-key (:stroke-alignment @state)))
        :set (fn [v] (swap! state assoc :stroke-alignment (parser/parse-keyword v)) (on-change!))}

       :strokeCapStart
       {:get (fn [] (format/format-key (:stroke-cap-start @state)))
        :set (fn [v] (swap! state assoc :stroke-cap-start (parser/parse-keyword v)) (on-change!))}

       :strokeCapEnd
       {:get (fn [] (format/format-key (:stroke-cap-end @state)))
        :set (fn [v] (swap! state assoc :stroke-cap-end (parser/parse-keyword v)) (on-change!))}

       :strokeColorGradient
       {:get (fn []
               (when-let [gradient (:stroke-color-gradient @state)]
                 (let [gradient-state (atom gradient)
                       gradient-change! (fn []
                                          (swap! state assoc :stroke-color-gradient @gradient-state)
                                          (on-change!))]
                   (gradients/gradient-proxy gradient-state gradient-change!))))
        :set (fn [v]
               (swap! state #(-> % (assoc :stroke-color-gradient (parser/parse-gradient v)) (dissoc :stroke-color :stroke-image)))
               (on-change!))}

       :strokeImage
       {:get (fn [] (format/format-image (:stroke-image @state)))
        :set (fn [v]
               (swap! state #(-> % (assoc :stroke-image (parser/parse-image-data v)) (dissoc :stroke-color :stroke-color-gradient)))
               (on-change!))}))))

(defn format-strokes
  ([strokes] (format-strokes strokes nil nil))
  ([strokes commit-fn] (format-strokes strokes commit-fn nil))
  ([strokes commit-fn options]
   (if (and (some? strokes) (fn? commit-fn))
     (let [arr-ref    (atom nil)
           on-change! (fn [] (commit-fn @arr-ref))
           arr        (apply array (mapv #(stroke-proxy % on-change! options) strokes))]
       (reset! arr-ref arr)
       arr)
     (format/format-array format/format-stroke strokes))))
