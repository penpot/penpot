;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.scroll-bars
  (:require
   [app.common.colors :as clr]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.viewport-ref :refer [point->viewport]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def scroll-x 10)
(def scroll-y 10)
(def scroll-height (+ scroll-x 4))
(def scroll-width (+ scroll-y 4))
(def other-x 26)
(def other-y 26)
(def other-width 100)
(def other-height 100)


(mf/defc viewport-scrollbars
  {::mf/wrap [mf/memo]}
  [{:keys [objects zoom vbox bottom-padding]}]

  (let [v-scrolling?              (mf/use-state false)
        h-scrolling?              (mf/use-state false)
        start-ref                 (mf/use-ref nil)
        v-scrollbar-y-ref         (mf/use-ref nil)
        h-scrollbar-x-ref         (mf/use-ref nil)
        v-scrollbar-y-stored      (mf/ref-val v-scrollbar-y-ref)
        h-scrollbar-x-stored      (mf/ref-val h-scrollbar-x-ref)
        v-scrollbar-y-padding-ref (mf/use-ref nil)
        h-scrollbar-x-padding-ref (mf/use-ref nil)
        scrollbar-height-ref      (mf/use-ref nil)
        scrollbar-width-ref       (mf/use-ref nil)
        scrollbar-height-stored   (mf/ref-val scrollbar-height-ref)
        scrollbar-width-stored    (mf/ref-val scrollbar-width-ref)
        height-factor-ref         (mf/use-ref nil)
        width-factor-ref          (mf/use-ref nil)
        vbox-y-ref                (mf/use-ref nil)
        vbox-x-ref                (mf/use-ref nil)

        vbox-x                    (:x vbox)
        vbox-y                    (:y vbox)

        base-objects-rect         (mf/with-memo [objects]
                                    (-> objects
                                        (cfh/get-immediate-children)
                                        (gsh/shapes->rect)))

        ;; Padding for bottom palette
        vbox                     (cond-> vbox
                                   (some? bottom-padding)
                                   (update :height - (/ bottom-padding zoom)))

        inv-zoom                 (/ 1 zoom)
        vbox-height              (- (:height vbox) (* inv-zoom scroll-height))
        vbox-width               (- (:width vbox) (* inv-zoom scroll-width))

        ;; top space hidden because of the scroll
        top-offset               (-> (- vbox-y (:y base-objects-rect))
                                     (max 0)
                                     (* vbox-height)
                                     (/ (:height base-objects-rect)))

        ;; left space hidden because of the scroll
        left-offset              (-> (- vbox-x (:x base-objects-rect))
                                     (max 0)
                                     (* vbox-width)
                                     (/ (:width base-objects-rect)))

        ;; bottom space hidden because of the scroll
        bottom-offset            (-> (- (:y2 base-objects-rect) (+ vbox-y vbox-height))
                                     (max 0)
                                     (* vbox-height)
                                     (/ (:height base-objects-rect)))

        ;; right space hidden because of the scroll
        right-offset             (-> (- (:x2 base-objects-rect) (+ vbox-x vbox-width))
                                     (max 0)
                                     (* vbox-width)
                                     (/ (:width base-objects-rect)))

        show-v-scroll?           (or @v-scrolling? (> top-offset 0) (> bottom-offset 0))
        show-h-scroll?           (or @h-scrolling? (> left-offset 0) (> right-offset 0))

        v-scrollbar-x            (+ vbox-x (:width vbox) (* inv-zoom (- scroll-x)))
        v-scrollbar-y            (+ vbox-y top-offset)

        h-scrollbar-x            (+ vbox-x left-offset)
        h-scrollbar-y            (+ vbox-y (:height vbox) (* inv-zoom (- scroll-y)))

        scrollbar-height         (-> (- (+ vbox-y vbox-height) bottom-offset v-scrollbar-y))
        scrollbar-height         (-> (cond
                                       @v-scrolling? scrollbar-height-stored
                                       :else scrollbar-height)
                                     (max (* inv-zoom other-height)))

        scrollbar-width          (-> (- (+ vbox-x vbox-width) right-offset h-scrollbar-x))
        scrollbar-width          (-> (cond
                                       @h-scrolling? scrollbar-width-stored
                                       :else scrollbar-width)
                                     (max (* inv-zoom other-width)))

        v-scrollbar-y            (-> (cond
                                       @v-scrolling? (- v-scrollbar-y-stored (- (- vbox-y (mf/ref-val vbox-y-ref))))
                                       :else v-scrollbar-y)
                                     (max (+ vbox-y (* inv-zoom other-y))))

        v-scrollbar-y            (if (> (+ v-scrollbar-y scrollbar-height) (+ vbox-y vbox-height)) ;; the scroll bar is stick to the bottom
                                   (-> (+ vbox-y vbox-height)
                                       (- scrollbar-height))
                                   v-scrollbar-y)

        h-scrollbar-x            (-> (cond
                                       @h-scrolling? (- h-scrollbar-x-stored (- (- vbox-x (mf/ref-val vbox-x-ref))))
                                       :else h-scrollbar-x)
                                     (max (+ vbox-x (* inv-zoom other-x))))

        h-scrollbar-x            (if (> (+ h-scrollbar-x scrollbar-width) (+ vbox-x vbox-width)) ;; the scroll bar is stick to the right
                                   (-> (+ vbox-x vbox-width)
                                       (- scrollbar-width))
                                   h-scrollbar-x)

        on-pointer-move
        (fn [event axis]
          (when-let [_ (or @v-scrolling? @h-scrolling?)]
            (let [start-pt            (mf/ref-val start-ref)
                  current-pt          (dom/get-client-position event)
                  current-pt-viewport (point->viewport current-pt)
                  y-delta               (/ (* (mf/ref-val height-factor-ref) (- (:y current-pt) (:y start-pt))) zoom)
                  x-delta               (/ (* (mf/ref-val width-factor-ref) (- (:x current-pt) (:x start-pt))) zoom)
                  new-v-scrollbar-y   (-> current-pt-viewport
                                          (:y)
                                          (+ (mf/ref-val v-scrollbar-y-padding-ref)))
                  new-h-scrollbar-x (-> current-pt-viewport
                                        (:x)
                                        (+ (mf/ref-val h-scrollbar-x-padding-ref)))
                  viewport-update            (-> {}
                                                 (cond-> (= axis :y) (assoc :y #(+ % y-delta)))
                                                 (cond-> (= axis :x) (assoc :x #(+ % x-delta))))]
              (mf/set-ref-val! vbox-y-ref vbox-y)
              (mf/set-ref-val! vbox-x-ref vbox-x)
              (st/emit! (dw/update-viewport-position viewport-update))
              (mf/set-ref-val! v-scrollbar-y-ref new-v-scrollbar-y)
              (mf/set-ref-val! h-scrollbar-x-ref new-h-scrollbar-x)
              (mf/set-ref-val! start-ref current-pt))))

        on-pointer-down
        (fn [event axis]
          (let [start-pt              (dom/get-client-position event)
                viewport-point        (point->viewport start-pt)
                new-h-scrollbar-x     (:x viewport-point)
                new-v-scrollbar-y     (:y viewport-point)
                v-scrollbar-y-padding (- v-scrollbar-y new-v-scrollbar-y)
                h-scrollbar-x-padding (- h-scrollbar-x new-h-scrollbar-x)
                vbox-rect             {:x vbox-x
                                       :y vbox-y
                                       :x1 vbox-x
                                       :y1 vbox-y
                                       :x2 (+ vbox-x (:width vbox))
                                       :y2 (+ vbox-y (:height vbox))
                                       :width (:width vbox)
                                       :height (:height vbox)}

                containing-rect       (grc/join-rects [base-objects-rect vbox-rect])
                height-factor         (/ (:height containing-rect) vbox-height)
                width-factor          (/ (:width containing-rect) vbox-width)]

            (mf/set-ref-val! start-ref start-pt)
            (mf/set-ref-val! v-scrollbar-y-padding-ref v-scrollbar-y-padding)
            (mf/set-ref-val! h-scrollbar-x-padding-ref h-scrollbar-x-padding)
            (mf/set-ref-val! v-scrollbar-y-ref (+ new-v-scrollbar-y v-scrollbar-y-padding))
            (mf/set-ref-val! h-scrollbar-x-ref (+ new-h-scrollbar-x h-scrollbar-x-padding))
            (mf/set-ref-val! vbox-y-ref vbox-y)
            (mf/set-ref-val! vbox-x-ref vbox-x)
            (mf/set-ref-val! scrollbar-height-ref scrollbar-height)
            (mf/set-ref-val! scrollbar-width-ref scrollbar-width)
            (mf/set-ref-val! height-factor-ref height-factor)
            (mf/set-ref-val! width-factor-ref width-factor)
            (reset! v-scrolling? (= axis :y))
            (reset! h-scrolling? (= axis :x))))

        on-pointer-up
        (fn []
          (reset! v-scrolling? false)
          (reset! h-scrolling? false))]

    [:*
     (when show-v-scroll?
       [:g.v-scroll {:fill clr/black
                     :data-testid "vertical-scrollbar"}
        [:rect {:on-pointer-move #(on-pointer-move % :y)
                :on-pointer-down #(on-pointer-down % :y)
                :on-pointer-up   on-pointer-up
                :width (* inv-zoom 7)
                :rx (* inv-zoom 3)
                :ry (* inv-zoom 3)
                :height scrollbar-height
                :fill-opacity 0.4
                :x v-scrollbar-x
                :y v-scrollbar-y
                :style {:stroke "white"
                        :stroke-width (/ 0.15 zoom)}}]])
     (when show-h-scroll?
       [:g.h-scroll {:fill clr/black
                     :data-testid "horizontal-scrollbar"}
        [:rect {:on-pointer-move #(on-pointer-move % :x)
                :on-pointer-down #(on-pointer-down % :x)
                :on-pointer-up  on-pointer-up
                :width scrollbar-width
                :rx (* inv-zoom 3)
                :ry (* inv-zoom 3)
                :height (* inv-zoom 7)
                :fill-opacity 0.4
                :x h-scrollbar-x
                :y h-scrollbar-y
                :style {:stroke "white"
                        :stroke-width (/ 0.15 zoom)}}]])]))
