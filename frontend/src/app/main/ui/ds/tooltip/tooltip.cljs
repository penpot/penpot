;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.tooltip.tooltip
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(defn- calculate-tooltip-rect [tooltip trigger-rect placement offset]
  (let [{trigger-top    :top
         trigger-left   :left
         trigger-right  :right
         trigger-bottom :bottom
         trigger-width  :width
         trigger-height :height} trigger-rect

        {tooltip-width  :width
         tooltip-height :height} (dom/get-bounding-rect tooltip)

        offset (d/nilv offset 2)
        arrow-height 12
        half-arrow-height (/ arrow-height 2)
        overlay-offset 32]

    (case placement
      "bottom"
      {:top (+ trigger-bottom offset)
       :left (- (+ trigger-left (/ trigger-width 2)) (/ tooltip-width 2))
       :right (+ trigger-left (/ trigger-width 2) (/ tooltip-width 2))
       :bottom (+ (- trigger-bottom offset) tooltip-height)
       :width tooltip-width
       :height tooltip-height}

      "left"
      {:top (- (+ trigger-top (/ trigger-height 2) half-arrow-height) (/ tooltip-height 2))
       :left (- trigger-left tooltip-width arrow-height)
       :right (+ (- trigger-left tooltip-width) tooltip-width)
       :bottom (+ (- (+ trigger-top (/ trigger-height 2) half-arrow-height) (/ tooltip-height 2)) tooltip-height)
       :width tooltip-width
       :height tooltip-height}

      "right"
      {:top (- (+ trigger-top (/ trigger-height 2) half-arrow-height) (/ tooltip-height 2))
       :left (+ trigger-right offset)
       :right (+ trigger-right offset tooltip-width)
       :bottom (+ (- (+ trigger-top (/ trigger-height 2) half-arrow-height) (/ tooltip-height 2)) tooltip-height)
       :width tooltip-width
       :height tooltip-height}

      "bottom-right"
      {:top (+ trigger-bottom offset)
       :left (- trigger-right overlay-offset)
       :right (+ (- trigger-right overlay-offset) tooltip-width)
       :bottom (+ (- trigger-bottom offset) tooltip-height)
       :width tooltip-width
       :height tooltip-height}

      "bottom-left"
      {:top (+ trigger-bottom offset)
       :left (+ (- trigger-left tooltip-width) overlay-offset)
       :right (+ trigger-left overlay-offset)
       :bottom (+ (- trigger-bottom offset) tooltip-height)
       :width tooltip-width
       :height tooltip-height}

      "top-right"
      {:top (- trigger-top offset tooltip-height)
       :left (- trigger-right overlay-offset)
       :right (+ (- trigger-right overlay-offset) tooltip-width)
       :bottom (- trigger-top offset)
       :width tooltip-width
       :height tooltip-height}

      "top-left"
      {:top (- trigger-top offset tooltip-height)
       :left (+ (- trigger-left tooltip-width) overlay-offset)
       :right (+ trigger-left overlay-offset)
       :bottom (- trigger-top offset)
       :width tooltip-width
       :height tooltip-height}

      {:top (- trigger-top offset tooltip-height)
       :left (- (+ trigger-left (/ trigger-width 2)) (/ tooltip-width 2))
       :right (+ (- (+ trigger-left (/ trigger-width 2)) (/ tooltip-width 2)) tooltip-width)
       :bottom (- trigger-top offset)
       :width tooltip-width
       :height tooltip-height})))

(defn- get-fallback-order [placement]
  (case placement
    "top" ["top" "right" "bottom" "left" "top-right" "bottom-right" "bottom-left" "top-left"]
    "bottom" ["bottom" "left" "top" "right" "bottom-right" "bottom-left" "top-left" "top-right"]
    "left" ["left" "top" "right" "bottom" "top-left" "top-right" "bottom-right" "bottom-left"]
    "right" ["right" "bottom" "left" "top" "bottom-left" "top-left" "top-right" "bottom-right"]
    "top-right" ["top-right" "right" "bottom" "left" "top" "bottom-right" "bottom-left" "top-left"]
    "bottom-right" ["bottom-right" "bottom" "left" "top" "right" "bottom-left" "top-left" "top-right"]
    "bottom-left" ["bottom-left" "left" "top" "right" "bottom"  "top-left" "top-right" "bottom-right"]
    "top-left" ["top-left" "top" "right" "bottom" "left" "bottom-left" "top-right" "bottom-right"]))

(def ^:private schema:tooltip
  [:map
   [:class {:optional true} :string]
   [:id {:optional true} :string]
   [:offset {:optional true} :int]
   [:delay {:optional true} :int]
   [:placement {:optional true}
    [:maybe [:enum "top" "bottom" "left" "right" "top-right" "bottom-right" "bottom-left" "top-left"]]]])

(mf/defc tooltip*
  {::mf/schema schema:tooltip}
  [{:keys [class id children tooltip-content placement offset delay] :rest props}]
  (let [id         (or id (mf/use-id))
        placement* (mf/use-state #(d/nilv placement "top"))
        placement  (deref placement*)
        delay      (d/nilv delay 300)

        schedule-ref (mf/use-ref nil)

        position-tooltip
        (fn [^js tooltip trigger-rect]
          (let [all-placements (get-fallback-order placement)]
            (when (.-isConnected tooltip)
              (.showPopover ^js tooltip))
            (loop [[current-placement & remaining-placements] all-placements]
              (when current-placement
                (reset! placement* current-placement)
                (let [tooltip-rect (calculate-tooltip-rect tooltip trigger-rect current-placement offset)]
                  (if (dom/is-bounding-rect-outside? tooltip-rect)
                    (recur remaining-placements)
                    (do (dom/set-css-property! tooltip "display" "grid")
                        (dom/set-css-property! tooltip "inset-block-start" (dm/str (:top tooltip-rect) "px"))
                        (dom/set-css-property! tooltip "inset-inline-start" (dm/str (:left tooltip-rect) "px")))))))))

        on-show
        (mf/use-fn
         (mf/deps id placement)
         (fn [event]
           (when-let [schedule (mf/ref-val schedule-ref)]
             (ts/dispose! schedule)
             (mf/set-ref-val! schedule-ref nil))
           (when-let [tooltip (dom/get-element id)]
             (let [trigger-rect (->> (dom/get-target event)
                                     (dom/get-bounding-rect))]
               (mf/set-ref-val!
                schedule-ref
                (ts/schedule
                 delay
                 #(position-tooltip tooltip trigger-rect)))))))

        on-hide
        (mf/use-fn
         (mf/deps id)
         (fn [] (when-let [tooltip (dom/get-element id)]
                  (when-let [schedule (mf/ref-val schedule-ref)]
                    (ts/dispose! schedule)
                    (mf/set-ref-val! schedule-ref nil))
                  (dom/unset-css-property! tooltip "display")
                  (.hidePopover ^js tooltip))))

        handle-key-down
        (mf/use-fn
         (mf/deps on-hide)
         (fn [event]
           (when (kbd/esc? event)
             (on-hide))))

        class (d/append-class class  (stl/css-case
                                      :tooltip true
                                      :tooltip-top (= placement "top")
                                      :tooltip-bottom (= placement "bottom")
                                      :tooltip-left (= placement "left")
                                      :tooltip-right (= placement "right")
                                      :tooltip-top-right (= placement "top-right")
                                      :tooltip-bottom-right (= placement "bottom-right")
                                      :tooltip-bottom-left (= placement "bottom-left")
                                      :tooltip-top-left (= placement "top-left")))

        props (mf/spread-props props {:on-mouse-enter on-show
                                      :on-mouse-leave on-hide
                                      :on-focus on-show
                                      :on-blur on-hide
                                      :on-key-down handle-key-down
                                      :class (stl/css :tooltip-trigger)
                                      :aria-describedby id})]
    [:> :div props
     children
     [:div {:class class
            :id id
            :popover "auto"
            :role "tooltip"}
      [:div {:class (stl/css :tooltip-content)}
       (if (fn? tooltip-content)
         (tooltip-content)
         tooltip-content)]
      [:div {:class (stl/css :tooltip-arrow)
             :id "tooltip-arrow"}]]]))