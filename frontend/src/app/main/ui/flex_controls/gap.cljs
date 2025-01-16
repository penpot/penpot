;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.flex-controls.gap
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.flex-layout :as gsl]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.flex-controls.common :as fcc]
   [app.main.ui.workspace.viewport.viewport-ref :refer [point->viewport]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc gap-display
  [{:keys [frame-id zoom gap-type gap on-pointer-enter on-pointer-leave
           rect-data hover? selected? mouse-pos hover-value
           on-move-selected on-context-menu]}]
  (let [resizing             (mf/use-var nil)
        start                (mf/use-var nil)
        original-value       (mf/use-var 0)
        negate?              (:resize-negate? rect-data)
        axis                 (:resize-axis rect-data)

        on-pointer-down
        (mf/use-fn
         (mf/deps frame-id gap-type gap)
         (fn [event]
           (dom/capture-pointer event)
           (reset! resizing gap-type)
           (reset! start (dom/get-client-position event))
           (reset! original-value (:initial-value rect-data))))

        on-lost-pointer-capture
        (mf/use-fn
         (mf/deps frame-id gap-type gap)
         (fn [event]
           (dom/release-pointer event)
           (reset! resizing nil)
           (reset! start nil)
           (reset! original-value 0)
           (st/emit! (dwm/apply-modifiers))))

        on-pointer-move
        (mf/use-fn
         (mf/deps frame-id gap-type gap)
         (fn [event]
           (let [pos (dom/get-client-position event)]
             (reset! mouse-pos (point->viewport pos))
             (when (= @resizing gap-type)
               (let [delta      (-> (gpt/to-vec @start pos)
                                    (cond-> negate? gpt/negate)
                                    (get axis))
                     val            (int (max (+ @original-value (/ delta zoom)) 0))
                     layout-gap (assoc gap gap-type val)
                     modifiers  (dwm/create-modif-tree [frame-id] (ctm/change-property (ctm/empty) :layout-gap layout-gap))]

                 (reset! hover-value val)
                 (st/emit! (dwm/set-modifiers modifiers)))))))]

    [:g.gap-rect
     [:rect.info-area
      {:x (:x rect-data)
       :y (:y rect-data)
       :width (:width rect-data)
       :height (:height rect-data)
       :on-pointer-enter on-pointer-enter
       :on-pointer-leave on-pointer-leave
       :on-pointer-move on-pointer-move
       :on-pointer-down on-move-selected
       :on-context-menu on-context-menu

       :style {:fill (if (or hover? selected?) fcc/distance-color "none")
               :opacity (if selected? 0.5 0.25)}}]

     (let [handle-width
           (if (= axis :x)
             (/ 2 zoom)
             (min (* (:width rect-data) 0.5) (/ 20 zoom)))

           handle-height
           (if (= axis :y)
             (/ 2 zoom)
             (min (* (:height rect-data) 0.5) (/ 30 zoom)))]
       [:rect.handle
        {:x (+ (:x rect-data) (/ (- (:width rect-data) handle-width) 2))
         :y (+ (:y rect-data) (/ (- (:height rect-data) handle-height) 2))
         :width handle-width
         :height handle-height
         :on-pointer-enter on-pointer-enter
         :on-pointer-leave on-pointer-leave
         :on-pointer-down on-pointer-down
         :on-lost-pointer-capture on-lost-pointer-capture
         :on-pointer-move on-pointer-move
         :on-context-menu on-context-menu
         :class (when (or hover? selected?)
                  (if (= (:resize-axis rect-data) :x) (cur/get-dynamic "resize-ew" 0) (cur/get-dynamic "resize-ew" 90)))
         :style {:fill (if (or hover? selected?) fcc/distance-color "none")
                 :opacity (if selected? 0 1)}}])]))

(mf/defc gap-rects
  [{:keys [frame zoom on-move-selected on-context-menu]}]
  (let [frame-id                   (:id frame)
        saved-dir                  (:layout-flex-dir frame)
        is-col?                    (or (= :column saved-dir) (= :column-reverse saved-dir))
        flip-x                     (:flip-x frame)
        flip-y                     (:flip-y frame)
        pill-width                 (/ fcc/flex-display-pill-width zoom)
        pill-height                (/ fcc/flex-display-pill-height zoom)
        workspace-modifiers        (mf/deref refs/workspace-modifiers)
        gap-selected               (mf/deref refs/workspace-gap-selected)
        hover                      (mf/use-state nil)
        hover-value                (mf/use-state 0)
        mouse-pos                  (mf/use-state nil)
        padding                    (:layout-padding frame)
        gap                        (:layout-gap frame)
        {:keys [width height x1 y1]} (:selrect frame)
        on-pointer-enter           (fn [hover-type val]
                                     (reset! hover hover-type)
                                     (reset! hover-value val))

        on-pointer-leave           #(reset! hover nil)
        negate                     {:column-gap (if flip-x true false)
                                    :row-gap (if flip-y true false)}

        objects                    (dsh/lookup-page-objects @st/state)
        children              (->> (cfh/get-immediate-children objects frame-id)
                                   (remove ctl/position-absolute?))

        children-to-display (if (or (= :row-reverse saved-dir)
                                    (= :column-reverse saved-dir))
                              (drop-last children)
                              (rest children))
        children-to-display (->> children-to-display
                                 (map #(gsh/transform-shape % (get-in workspace-modifiers [(:id %) :modifiers]))))

        wrap-blocks
        (let [block-children (->> children
                                  (map #(vector (gpo/parent-coords-bounds (:points %) (:points frame)) %)))
              bounds (d/lazy-map (keys objects) #(gsh/shape->points (get objects %)))

              layout-data (gsl/calc-layout-data frame (:points frame) block-children bounds objects)
              layout-bounds (:layout-bounds layout-data)
              xv   #(gpo/start-hv layout-bounds %)
              yv   #(gpo/start-vv layout-bounds %)]
          (for [{:keys [start-p line-width line-height layout-gap-row layout-gap-col num-children]} (:layout-lines layout-data)]
            (let [line-width (if is-col? line-width (+ line-width (* (dec num-children) layout-gap-row)))
                  line-height (if is-col? (+ line-height (* (dec num-children) layout-gap-col)) line-height)
                  end-p (-> start-p (gpt/add (xv line-width)) (gpt/add (yv line-height)))]
              {:x1 (min (:x start-p) (:x end-p))
               :y1 (min (:y start-p) (:y end-p))
               :x2 (max (:x start-p) (:x end-p))
               :y2 (max (:y start-p) (:y end-p))})))

        block-contains
        (fn [x y block]
          (if is-col?
            (<= (:x1 block) x (:x2 block))
            (<= (:y1 block) y (:y2 block))))

        get-container-block
        (fn [shape]
          (let [selrect (:selrect shape)
                x (/ (+ (:x1 selrect) (:x2 selrect)) 2)
                y (/ (+ (:y1 selrect) (:y2 selrect)) 2)]
            (->> wrap-blocks
                 (filter #(block-contains x y %))
                 first)))

        create-cgdd
        (fn [shape]
          (let [block  (get-container-block shape)
                x (if flip-x
                    (- (:x1 (:selrect shape))
                       (get-in shape [:layout-item-margin :m2])
                       (:column-gap gap))
                    (+ (:x2 (:selrect shape)) (get-in shape [:layout-item-margin :m2])))
                y (:y1 block)
                h (- (:y2 block) (:y1 block))]
            {:x x
             :y y
             :height h
             :width (:column-gap gap)
             :initial-value (:column-gap gap)
             :resize-type :left
             :resize-axis :x
             :resize-negate? (:column-gap negate)
             :gap-type (if is-col? :row-gap :column-gap)}))

        create-cgdd-block
        (fn [block]
          (let [x (if flip-x
                    (- (:x1 block) (:column-gap gap))
                    (:x2 block))
                y (if flip-y
                    (+ y1 (:p3 padding))
                    (+ y1 (:p1 padding)))
                h (- height (+ (:p1 padding) (:p3 padding)))]
            {:x x
             :y y
             :width (:column-gap gap)
             :height h
             :initial-value (:column-gap gap)
             :resize-type :left
             :resize-axis :x
             :resize-negate? (:column-gap negate)
             :gap-type (if is-col? :column-gap :row-gap)}))

        create-rgdd
        (fn [shape]
          (let [block  (get-container-block shape)
                x (:x1 block)
                y (if flip-y
                    (- (:y1 (:selrect shape))
                       (get-in shape [:layout-item-margin :m3])
                       (:row-gap gap))
                    (+ (:y2 (:selrect shape)) (get-in shape [:layout-item-margin :m3])))
                w (- (:x2 block) (:x1 block))]
            {:x x
             :y y
             :width w
             :height (:row-gap gap)
             :initial-value (:row-gap gap)
             :resize-type :bottom
             :resize-axis :y
             :resize-negate? (:row-gap negate)
             :gap-type (if is-col? :row-gap :column-gap)}))

        create-rgdd-block
        (fn [block]
          (let [x (if flip-x
                    (+ x1 (:p2 padding))
                    (+ x1 (:p4 padding)))
                y (if flip-y
                    (- (:y1 block) (:row-gap gap))
                    (:y2 block))
                w (- width (+ (:p2 padding) (:p4 padding)))]
            {:x x
             :y y
             :width w
             :height (:row-gap gap)
             :initial-value (:row-gap gap)
             :resize-type :bottom
             :resize-axis :y
             :resize-negate? (:row-gap negate)
             :gap-type (if is-col? :column-gap :row-gap)}))

        display-blocks (if is-col?
                         (->> (drop-last wrap-blocks)
                              (map create-cgdd-block))
                         (->> (drop-last wrap-blocks)
                              (map create-rgdd-block)))

        display-children (if is-col?
                           (->> children-to-display
                                (map create-rgdd))
                           (->> children-to-display
                                (map create-cgdd)))]

    [:g.gaps {:pointer-events "visible"}
     (for [[index display-item] (d/enumerate (concat display-blocks display-children))]
       (let [gap-type (:gap-type display-item)]
         [:& gap-display {:key (str frame-id index)
                          :frame-id frame-id
                          :zoom zoom
                          :gap-type gap-type
                          :gap gap
                          :on-pointer-enter (partial on-pointer-enter gap-type (get gap gap-type))
                          :on-pointer-leave on-pointer-leave
                          :on-move-selected on-move-selected
                          :on-context-menu on-context-menu
                          :rect-data display-item
                          :hover?    (= @hover gap-type)
                          :selected? (= gap-selected gap-type)
                          :mouse-pos mouse-pos
                          :hover-value hover-value}]))

     (when @hover
       [:& fcc/flex-display-pill
        {:height pill-height
         :width pill-width
         :font-size (/ fcc/font-size zoom)
         :border-radius (/ fcc/flex-display-pill-border-radius zoom)
         :color fcc/distance-color
         :x (:x @mouse-pos)
         :y (- (:y @mouse-pos) pill-width)
         :value @hover-value}])]))


(mf/defc gap-control
  [{:keys [frame zoom  on-move-selected on-context-menu]}]
  (when frame
    [:g.measurement-gaps {:pointer-events "none"}
     [:g.hover-shapes
      [:& gap-rects
       {:frame frame
        :zoom zoom
        :on-move-selected on-move-selected
        :on-context-menu on-context-menu}]]]))
