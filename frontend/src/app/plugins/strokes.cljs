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
  "Sets one side width and reports the edited attribute to the commit callback.
  Rejects shape types that do not support per-side widths."
  [state attr value on-change! per-side-allowed? not-valid]
  (if (and (false? per-side-allowed?) (fn? not-valid))
    (not-valid attr value)
    (do
      (swap! state #(cts/set-width-to-single-side % attr value))
      (on-change! [attr]))))

(defn stroke-proxy
  ([stroke-data on-change!]
   (stroke-proxy stroke-data on-change! nil))
  ([stroke-data on-change! {:keys [per-side-allowed? not-valid]}]
   (let [state (atom stroke-data)]
     (obj/reify {:name "StrokeProxy"}
       ;; The parser reads stored widths without invoking fallback getters.
       :$state {:enumerable false :get (fn [] @state)}

       :strokeColor
       {:get (fn [] (:stroke-color @state))
        :set (fn [v]
               (swap! state #(-> % (assoc :stroke-color v) (dissoc :stroke-color-gradient :stroke-image)))
               (on-change! [:stroke-color]))}

       :strokeColorRefFile
       {:get (fn [] (format/format-id (:stroke-color-ref-file @state)))
        :set (fn [v] (swap! state assoc :stroke-color-ref-file (parser/parse-id v)) (on-change! [:stroke-color]))}

       :strokeColorRefId
       {:get (fn [] (format/format-id (:stroke-color-ref-id @state)))
        :set (fn [v] (swap! state assoc :stroke-color-ref-id (parser/parse-id v)) (on-change! [:stroke-color]))}

       :strokeOpacity
       {:get (fn [] (:stroke-opacity @state))
        :set (fn [v] (swap! state assoc :stroke-opacity v) (on-change! [:stroke-color]))}

       :strokeStyle
       {:get (fn [] (format/format-key (:stroke-style @state)))
        :set (fn [v] (swap! state assoc :stroke-style (parser/parse-keyword v)) (on-change! [:stroke-style]))}

       :strokeWidth
       {:get (fn [] (:stroke-width @state))
        :set (fn [v]
               (swap! state #(cts/set-width-to-all-sides % v))
               (on-change! [:stroke-width]))}

       :strokeWidthType
       {:get (fn [] (format/format-key (cts/width-type @state)))}

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
        :set (fn [v] (swap! state assoc :stroke-alignment (parser/parse-keyword v)) (on-change! [:stroke-alignment]))}

       :strokeCapStart
       {:get (fn [] (format/format-key (:stroke-cap-start @state)))
        :set (fn [v] (swap! state assoc :stroke-cap-start (parser/parse-keyword v)) (on-change! [:stroke-cap-start]))}

       :strokeCapEnd
       {:get (fn [] (format/format-key (:stroke-cap-end @state)))
        :set (fn [v] (swap! state assoc :stroke-cap-end (parser/parse-keyword v)) (on-change! [:stroke-cap-end]))}

       :strokeColorGradient
       {:get (fn []
               (when-let [gradient (:stroke-color-gradient @state)]
                 (let [gradient-state (atom gradient)
                       gradient-change! (fn []
                                          (swap! state assoc :stroke-color-gradient @gradient-state)
                                          (on-change! [:stroke-color]))]
                   (gradients/gradient-proxy gradient-state gradient-change!))))
        :set (fn [v]
               (swap! state #(-> % (assoc :stroke-color-gradient (parser/parse-gradient v)) (dissoc :stroke-color :stroke-image)))
               (on-change! [:stroke-color]))}

       :strokeImage
       {:get (fn [] (format/format-image (:stroke-image @state)))
        :set (fn [v]
               (swap! state #(-> % (assoc :stroke-image (parser/parse-image-data v)) (dissoc :stroke-color :stroke-color-gradient)))
               (on-change! [:stroke-color]))}))))

(defn format-strokes
  ([strokes] (format-strokes strokes nil nil))
  ([strokes commit-fn] (format-strokes strokes commit-fn nil))
  ([strokes commit-fn options]
   (if (and (some? strokes) (fn? commit-fn))
     (let [arr-ref    (atom nil)
           on-change! (fn [index attrs]
                        (commit-fn @arr-ref {:changed-sub-attr attrs :changed-item-index index}))
           arr        (apply array
                             (map-indexed
                              (fn [index stroke]
                                (stroke-proxy stroke (partial on-change! index) options))
                              strokes))]
       (reset! arr-ref arr)
       arr)
     (format/format-array format/format-stroke strokes))))
