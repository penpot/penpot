;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.tooltip.tooltip
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(def ^:private ^:const overlay-offset 32)

(defonce active-tooltip (atom nil))

(defn- clear-schedule
  [ref]
  (when-let [schedule (mf/ref-val ref)]
    (ts/dispose! schedule)
    (mf/set-ref-val! ref nil)))

(defn- add-schedule
  [ref delay f]
  (mf/set-ref-val! ref (ts/schedule delay f)))

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
      {:top (- (+ trigger-top (/ trigger-height 2)) (/ tooltip-height 2))
       :left (- trigger-left tooltip-width)
       :right (+ (- trigger-left tooltip-width) tooltip-width)
       :bottom (+ (- (+ trigger-top (/ trigger-height 2)) (/ tooltip-height 2)) tooltip-height)
       :width tooltip-width
       :height tooltip-height}

      "right"
      {:top (- (+ trigger-top (/ trigger-height 2)) (/ tooltip-height 2))
       :left (+ trigger-right offset)
       :right (+ trigger-right offset tooltip-width)
       :bottom (+ (- (+ trigger-top (/ trigger-height 2)) (/ tooltip-height 2)) tooltip-height)
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

(def ^:private schema:tooltip
  [:map
   [:class {:optional true} [:maybe :string]]
   [:id {:optional true} :string]
   [:offset {:optional true} :int]
   [:delay {:optional true} :int]
   [:content [:or fn? :string map?]]
   [:trigger-ref {:optional true} [:maybe :any]]
   [:placement {:optional true}
    [:maybe [:enum "top" "bottom" "left" "right" "top-right" "bottom-right" "bottom-left" "top-left"]]]])

(mf/defc tooltip*
  {::mf/schema schema:tooltip}
  [{:keys [class id children content placement offset delay trigger-ref aria-label] :rest props}]
  (let [internal-id
        (mf/use-id)
        internal-trigger-ref (mf/use-ref nil)
        trigger-ref (or trigger-ref internal-trigger-ref)

        tooltip-ref (mf/use-ref nil)

        id
        (d/nilv id internal-id)

        tooltip-id
        (mf/use-id)

        placement*
        (mf/use-state #(d/nilv placement "top"))

        placement
        (deref placement*)

        delay
        (d/nilv delay 300)

        schedule-ref
        (mf/use-ref nil)

        visible*
        (mf/use-state false)
        visible (deref visible*)

        on-show
        (mf/use-fn
         (mf/deps tooltip-id delay)
         (fn [_]
           (when-not (.-hidden js/document)
             (let [trigger-el (mf/ref-val trigger-ref)]
               (clear-schedule schedule-ref)
               (add-schedule schedule-ref (d/nilv delay 300)
                             (fn []
                               (when-let [active @active-tooltip]
                                 (when (not= (:id active) tooltip-id)
                                   (when-let [tooltip-el (dom/get-element (:id active))]
                                     (dom/set-css-property! tooltip-el "display" "none"))
                                   (reset! active-tooltip nil)))
                               (reset! active-tooltip {:id tooltip-id :trigger trigger-el})
                               (reset! visible* true)))))))

        on-hide
        (mf/use-fn
         (mf/deps tooltip-id)
         (fn []
           (clear-schedule schedule-ref)
           (reset! visible* false)
           (when (= (:id @active-tooltip) tooltip-id)
             (reset! active-tooltip nil))))

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

        content
        (if (fn? content)
          (content)
          content)
        props
        (mf/spread-props props
                         {:on-mouse-enter on-show
                          :on-mouse-leave on-hide
                          :on-focus on-show
                          :on-blur on-hide
                          :ref internal-trigger-ref
                          :on-key-down handle-key-down
                          :id id
                          :class [class (stl/css :tooltip-trigger)]
                          :aria-label (if (string? content)
                                        content
                                        aria-label)})]

    (mf/use-effect
     (mf/deps tooltip-id)
     (fn []
       (let [handle-visibility-change
             (fn []
               (when (.-hidden js/document)
                 (on-hide)))]
         (js/document.addEventListener "visibilitychange" handle-visibility-change)
         ;; cleanup
         #(js/document.removeEventListener "visibilitychange" handle-visibility-change))))

    (mf/use-effect
     (mf/deps visible placement offset)
     (fn []
       (when visible
         (let [trigger-el (mf/ref-val trigger-ref)
               tooltip-el (mf/ref-val tooltip-ref)]
           (when (and trigger-el tooltip-el)
             (js/requestAnimationFrame
              (fn []
                (let [origin-brect (dom/get-bounding-rect trigger-el)
                      tooltip-brect (dom/get-bounding-rect tooltip-el)
                      window-size (dom/get-window-size)]
                  (when-let [[new-placement placement-rect]
                             (find-matching-placement
                              placement
                              tooltip-brect
                              origin-brect
                              window-size
                              offset)]
                    (dom/set-css-property! tooltip-el "inset-block-start"
                                           (str (:top placement-rect) "px"))
                    (dom/set-css-property! tooltip-el "inset-inline-start"
                                           (str (:left placement-rect) "px"))

                    (when (not= new-placement placement)
                      (reset! placement* new-placement)))))))))))

    [:> :div props
     children
     (when visible
       (mf/portal
        (mf/html
         [:div {:class (stl/css :tooltip)
                :role "tooltip"
                :id tooltip-id
                :ref tooltip-ref}
          [:div {:class tooltip-class}
           [:div {:class (stl/css :tooltip-content)} content]
           [:div {:class (stl/css :tooltip-arrow)
                  :id "tooltip-arrow"}]]])
        (.-body js/document)))]))
