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

(def ^:private ^:const arrow-height 12)
(def ^:private ^:const half-arrow-height (/ arrow-height 2))
(def ^:private ^:const overlay-offset 32)

(defn- clear-schedule
  [ref]
  (when-let [schedule (mf/ref-val ref)]
    (ts/dispose! schedule)
    (mf/set-ref-val! ref nil)))

(defn- add-schedule
  [ref delay f]
  (mf/set-ref-val! ref (ts/schedule delay f)))

(defn- show-popover
  [node]
  (when (.-isConnected ^js node)
    (.showPopover ^js node)))

(defn- hide-popover
  [node]
  (.hidePopover ^js node))

(defn- calculate-placement-bounding-rect
  "Given a placement, calcultates the bounding rect for it taking in
  account provided tooltip bounding rect and the origin bounding
  rect."
  [placement tooltip-brect origin-brect offset]
  (let [{trigger-top    :top
         trigger-left   :left
         trigger-right  :right
         trigger-bottom :bottom
         trigger-width  :width
         trigger-height :height}
        origin-brect

        {tooltip-width  :width
         tooltip-height :height}
        tooltip-brect

        offset (d/nilv offset 2)]

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

(defn- get-fallback-order
  "Get a vector of placement followed with ordered fallback pacements
  for the specified placement"
  [placement]
  (case placement
    "top" ["top" "right" "bottom" "left" "top-right" "bottom-right" "bottom-left" "top-left"]
    "bottom" ["bottom" "left" "top" "right" "bottom-right" "bottom-left" "top-left" "top-right"]
    "left" ["left" "top" "right" "bottom" "top-left" "top-right" "bottom-right" "bottom-left"]
    "right" ["right" "bottom" "left" "top" "bottom-left" "top-left" "top-right" "bottom-right"]
    "top-right" ["top-right" "right" "bottom" "left" "top" "bottom-right" "bottom-left" "top-left"]
    "bottom-right" ["bottom-right" "bottom" "left" "top" "right" "bottom-left" "top-left" "top-right"]
    "bottom-left" ["bottom-left" "left" "top" "right" "bottom"  "top-left" "top-right" "bottom-right"]
    "top-left" ["top-left" "top" "right" "bottom" "left" "bottom-left" "top-right" "bottom-right"]))

(defn- find-matching-placement
  "Algorithm for find a correct placement and placement-brect for the
  provided placement, if the current placement does not matches, it
  uses the predefined fallbacks. Returns an array of matched placement
  and its bounding rect."
  [placement tooltip-brect origin-brect window-size offset]
  (loop [placements (seq (get-fallback-order placement))]
    (when-let [placement (first placements)]
      (let [placement-brect (calculate-placement-bounding-rect placement tooltip-brect origin-brect offset)]
        (if (dom/is-bounding-rect-outside? placement-brect window-size)
          (recur (rest placements))
          #js [placement placement-brect])))))

(defn- update-tooltip-position
  "Update the tooltip position having in account the current window
  size, placement. It calculates the appropriate placement and updates
  the dom with the result."
  [tooltip placement origin-brect offset]
  (show-popover tooltip)
  (let [saved-height (dom/get-data tooltip "height")
        saved-width (dom/get-data tooltip "width")
        tooltip-brect (dom/get-bounding-rect tooltip)
        tooltip-brect (assoc tooltip-brect :height (or saved-height (:height tooltip-brect)) :width (or saved-width (:width tooltip-brect)))
        window-size   (dom/get-window-size)]
    (when-let [[placement placement-rect] (find-matching-placement placement tooltip-brect origin-brect window-size offset)]
      (let [height (:height placement-rect)]
        (dom/set-css-property! tooltip "block-size" (dm/str height "px"))
        (dom/set-css-property! tooltip "inset-block-start" (dm/str (:top placement-rect) "px"))
        (dom/set-css-property! tooltip "inset-inline-start" (dm/str (:left placement-rect) "px")))
      placement)))

(def ^:private schema:tooltip
  [:map
   [:class {:optional true} :string]
   [:id {:optional true} :string]
   [:offset {:optional true} :int]
   [:delay {:optional true} :int]
   [:content [:or fn? :string]]
   [:placement {:optional true}
    [:maybe [:enum "top" "bottom" "left" "right" "top-right" "bottom-right" "bottom-left" "top-left"]]]])

(mf/defc tooltip*
  {::mf/schema schema:tooltip}
  [{:keys [class id children content placement offset delay] :rest props}]
  (let [internal-id
        (mf/use-id)

        id
        (d/nilv id internal-id)

        placement*
        (mf/use-state #(d/nilv placement "top"))

        placement
        (deref placement*)

        delay
        (d/nilv delay 300)

        schedule-ref
        (mf/use-ref nil)

        on-show
        (mf/use-fn
         (mf/deps id placement offset)
         (fn [event]
           (clear-schedule schedule-ref)
           (when-let [tooltip (dom/get-element id)]
             (let [origin-brect
                   (->> (dom/get-target event)
                        (dom/get-bounding-rect))

                   update-position
                   (fn []
                     (let [new-placement (update-tooltip-position tooltip placement origin-brect offset)]
                       (when (not= new-placement placement)
                         (reset! placement* new-placement))))]

               (add-schedule schedule-ref delay update-position)))))

        on-hide
        (mf/use-fn
         (mf/deps id)
         (fn []
           (when-let [tooltip (dom/get-element id)]
             (clear-schedule schedule-ref)
             (hide-popover tooltip))))

        handle-key-down
        (mf/use-fn
         (mf/deps on-hide)
         (fn [event]
           (when (kbd/esc? event)
             (on-hide))))

        tooltip-class
        (stl/css-case
         :tooltip-content-wrapper true
         :tooltip-top (identical? placement "top")
         :tooltip-bottom (identical? placement "bottom")
         :tooltip-left (identical? placement "left")
         :tooltip-right (identical? placement "right")
         :tooltip-top-right (identical? placement "top-right")
         :tooltip-bottom-right (identical? placement "bottom-right")
         :tooltip-bottom-left (identical? placement "bottom-left")
         :tooltip-top-left (identical? placement "top-left"))

        props
        (mf/spread-props props
                         {:on-mouse-enter on-show
                          :on-mouse-leave on-hide
                          :on-focus on-show
                          :on-blur on-hide
                          :on-key-down handle-key-down
                          :class [class (stl/css :tooltip-trigger)]
                          :aria-describedby id})
        content
        (if (fn? content)
          (content)
          content)]

    [:> :div props
     children
     [:div {:class (stl/css :tooltip)
            :id id
            :popover "auto"
            :role "tooltip"}
      [:div {:class tooltip-class}
       [:div {:class (stl/css :tooltip-content)} content]
       [:div {:class (stl/css :tooltip-arrow)
              :id "tooltip-arrow"}]]]]))
