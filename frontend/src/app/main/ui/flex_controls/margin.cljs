;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.flex-controls.margin
  (:require
   [app.common.geom.point :as gpt]
   [app.common.types.modifiers :as ctm]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.flex-controls.common :as fcc]
   [app.main.ui.workspace.viewport.viewport-ref :refer [point->viewport]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc margin-display [{:keys [shape-id zoom hover-all? hover-v? hover-h? margin-num margin on-pointer-enter on-pointer-leave
                                 rect-data hover? selected? mouse-pos hover-value]}]
  (let [resizing?            (mf/use-var false)
        start                (mf/use-var nil)
        original-value       (mf/use-var 0)
        last-pos             (mf/use-var nil)
        negate?              (true? (:resize-negate? rect-data))
        axis                 (:resize-axis rect-data)

        on-pointer-down
        (mf/use-fn
         (mf/deps shape-id margin-num margin)
         (fn [event]
           (dom/capture-pointer event)
           (reset! resizing? true)
           (reset! start (dom/get-client-position event))
           (reset! original-value (:initial-value rect-data))))

        calc-modifiers
        (mf/use-fn
         (fn [pos]
           (let [delta
                 (-> (gpt/to-vec @start pos)
                     (cond-> negate? gpt/negate)
                     (get axis))

                 val
                 (int (max (+ @original-value (/ delta zoom)) 0))

                 layout-item-margin
                 (cond
                   hover-all? (assoc margin :m1 val :m2 val :m3 val :m4 val)
                   hover-v?   (assoc margin :m1 val :m3 val)
                   hover-h?   (assoc margin :m2 val :m4 val)
                   :else      (assoc margin margin-num val))

                 layout-item-margin-type
                 (if (= (:m1 margin) (:m2 margin) (:m3 margin) (:m4 margin)) :simple :multiple)]

             [val
              (dwm/create-modif-tree
               [shape-id]
               (-> (ctm/empty)
                   (ctm/change-property  :layout-item-margin layout-item-margin)
                   (ctm/change-property  :layout-item-margin-type layout-item-margin-type)))])))

        on-lost-pointer-capture
        (mf/use-fn
         (mf/deps shape-id margin-num margin)
         (fn [event]
           (dom/release-pointer event)

           (when (features/active-feature? @st/state "render-wasm/v1")
             (let [[_ modifiers]  (calc-modifiers @last-pos)]
               (st/emit! (dwm/apply-wasm-modifiers modifiers)
                         (dwt/finish-transform))))

           (reset! resizing? false)
           (reset! start nil)
           (reset! original-value 0)

           (when (not (features/active-feature? @st/state "render-wasm/v1"))
             (st/emit! (dwm/apply-modifiers)))))

        on-pointer-move
        (mf/use-fn
         (mf/deps shape-id margin-num margin hover-all? hover-v? hover-h?)
         (fn [event]
           (let [pos (dom/get-client-position event)]
             (reset! mouse-pos (point->viewport pos))
             (reset! last-pos pos)
             (when @resizing?
               (let [[val modifiers] (calc-modifiers pos)]
                 (reset! hover-value val)
                 (if (features/active-feature? @st/state "render-wasm/v1")
                   (st/emit! (dwm/set-wasm-modifiers modifiers))
                   (st/emit! (dwm/set-modifiers modifiers))))))))]

    [:rect.margin-rect
     {:x (:x rect-data)
      :y (:y rect-data)
      :width (max 0 (:width rect-data))
      :height (max 0 (:height rect-data))
      :on-pointer-enter on-pointer-enter
      :on-pointer-leave on-pointer-leave
      :on-pointer-down on-pointer-down
      :on-lost-pointer-capture on-lost-pointer-capture
      :on-pointer-move on-pointer-move
      :class (when (or hover? selected?)
               (if (= (:resize-axis rect-data) :x) (cur/get-dynamic "resize-ew" 0) (cur/get-dynamic "resize-ew" 90)))
      :style {:fill (if (or hover? selected?) fcc/warning-color "none")
              :opacity (if selected? 0.5 0.25)}}]))


(mf/defc margin-rects [{:keys [shape frame zoom alt? shift?]}]
  (let [shape-id                   (:id shape)
        pill-width                 (/ fcc/flex-display-pill-width zoom)
        pill-height                (/ fcc/flex-display-pill-height zoom)
        margins-selected           (mf/deref refs/workspace-margins-selected)
        hover-value                (mf/use-state 0)
        mouse-pos                  (mf/use-state nil)
        hover                      (mf/use-state nil)
        hover-all?                 (and (not (nil? @hover)) alt?)
        hover-v?                   (and (or (= @hover :m1) (= @hover :m3)) shift?)
        hover-h?                   (and (or (= @hover :m2) (= @hover :m4)) shift?)
        margin                    (:layout-item-margin shape)
        {:keys [width height x1 x2 y1 y2]} (:selrect shape)
        on-pointer-enter          (fn [hover-type val]
                                    (reset! hover hover-type)
                                    (reset! hover-value val))
        on-pointer-leave           #(reset! hover nil)
        hover? #(or hover-all?
                    (and (or (= % :m1) (= % :m3)) hover-v?)
                    (and (or (= % :m2) (= % :m4)) hover-h?)
                    (= @hover %))
        margin-display-data       {:m1 {:key (str shape-id "-m1")
                                        :x x1
                                        :y (if (:flip-y frame) y2 (- y1 (:m1 margin)))
                                        :width width
                                        :height (:m1 margin)
                                        :initial-value (:m1 margin)
                                        :resize-type :top
                                        :resize-axis :y
                                        :resize-negate? (:flip-y frame)}
                                   :m2 {:key (str shape-id "-m2")
                                        :x (if (:flip-x frame) (- x1 (:m2 margin)) x2)
                                        :y y1
                                        :width (:m2 margin)
                                        :height height
                                        :initial-value (:m2 margin)
                                        :resize-type :left
                                        :resize-axis :x
                                        :resize-negate? (:flip-x frame)}
                                   :m3 {:key (str shape-id "-m3")
                                        :x x1
                                        :y (if (:flip-y frame) (- y1 (:m3 margin)) y2)
                                        :width width
                                        :height (:m3 margin)
                                        :initial-value (:m3 margin)
                                        :resize-type :top
                                        :resize-axis :y
                                        :resize-negate? (:flip-y frame)}
                                   :m4 {:key (str shape-id "-m4")
                                        :x (if (:flip-x frame) x2 (- x1 (:m4 margin)))
                                        :y y1
                                        :width (:m4 margin)
                                        :height height
                                        :initial-value (:m4 margin)
                                        :resize-type :left
                                        :resize-axis :x
                                        :resize-negate? (:flip-x frame)}}]

    [:g.margins {:pointer-events "visible"}
     (for [[margin-num rect-data] margin-display-data]
       [:& margin-display
        {:key (:key rect-data)
         :shape-id shape-id
         :zoom zoom
         :hover-all? hover-all?
         :hover-v? hover-v?
         :hover-h? hover-h?
         :margin-num margin-num
         :margin margin
         :on-pointer-enter (partial on-pointer-enter margin-num (get margin margin-num))
         :on-pointer-leave on-pointer-leave
         :rect-data rect-data
         :hover?  (hover? margin-num)
         :selected? (get margins-selected margin-num)
         :mouse-pos mouse-pos
         :hover-value hover-value}])

     (when @hover
       [:& fcc/flex-display-pill
        {:height pill-height
         :width pill-width
         :font-size (/ fcc/font-size zoom)
         :border-radius (/ fcc/flex-display-pill-border-radius zoom)
         :color fcc/warning-color
         :x (:x @mouse-pos)
         :y (- (:y @mouse-pos) pill-width)
         :value @hover-value}])]))

(mf/defc margin-control
  [{:keys [shape parent zoom alt? shift?]}]
  (when shape
    [:g.measurement-gaps {:pointer-events "none"}
     [:g.hover-shapes
      [:& margin-rects
       {:shape shape
        :frame parent
        :zoom zoom
        :alt? alt?
        :shift? shift?}]]]))
