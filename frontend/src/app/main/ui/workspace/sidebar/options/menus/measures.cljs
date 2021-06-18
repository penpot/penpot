;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.measures
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as math]
   [app.common.pages.spec :as spec]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.changes :as dch]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def measure-attrs [:proportion-lock
                    :width :height
                    :x :y
                    :rotation
                    :rx :ry
                    :r1 :r2 :r3 :r4
                    :selrect
                    :constraints-h
                    :constraints-v
                    :fixed-scroll
                    :parent-id
                    :frame-id])

(defn- attr->string [attr values]
  (let [value (attr values)]
    (if (= value :multiple)
      ""
      (str (-> value
               (d/coalesce 0)
               (math/precision 2))))))

;; -- User/drawing coords
(mf/defc measures-menu
  [{:keys [options ids ids-with-children values] :as props}]
  (let [options (or options #{:size :position :rotation :radius})

        ids-with-children (or ids-with-children ids)

        old-shapes (deref (refs/objects-by-id ids))
        frames (map #(deref (refs/object-by-id (:frame-id %))) old-shapes)

        shapes (as-> old-shapes $
                 (map gsh/transform-shape $)
                 (map gsh/translate-to-frame $ frames))

        values (let [{:keys [x y]} (-> shapes first :points gsh/points->selrect)]
                 (cond-> values
                   (not= (:x values) :multiple) (assoc :x x)
                   (not= (:y values) :multiple) (assoc :y y)))

        values (let [{:keys [width height]} (-> shapes first :selrect)]
                 (cond-> values
                   (not= (:width values) :multiple) (assoc :width width)
                   (not= (:height values) :multiple) (assoc :height height)))

        proportion-lock (:proportion-lock values)

        in-frame? (not= (:parent-id values) uuid/zero)
        first-level? (and in-frame?
                          (= (:parent-id values) (:frame-id values)))

        constraints-h (get values :constraints-h (spec/default-constraints-h values))
        constraints-v (get values :constraints-v (spec/default-constraints-v values))

        on-size-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value attr]
           (st/emit! (udw/update-dimensions ids attr value))))

        on-proportion-lock-change
        (mf/use-callback
         (mf/deps ids)
         (fn [_]
           (let [new-lock (if (= proportion-lock :multiple) true (not proportion-lock))]
             (run! #(st/emit! (udw/set-shape-proportion-lock % new-lock)) ids))))

        do-position-change
        (mf/use-callback
         (mf/deps ids)
         (fn [shape' frame' value attr]
           (let [to (+ value (attr frame'))]
             (st/emit! (udw/update-position (:id shape') { attr to })))))

        on-position-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value attr]
           (doall (map #(do-position-change %1 %2 value attr) shapes frames))))

        on-rotation-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value]
           (st/emit! (udw/increase-rotation ids value))))

        on-switch-to-radius-1
        (mf/use-callback
         (mf/deps ids)
         (fn [_value]
           (let [radius-update
                 (fn [shape]
                   (cond-> shape
                     (:r1 shape)
                     (-> (assoc :rx 0 :ry 0)
                         (dissoc :r1 :r2 :r3 :r4))))]
             (st/emit! (dch/update-shapes ids-with-children radius-update)))))

        on-switch-to-radius-4
        (mf/use-callback
         (mf/deps ids)
         (fn [_value]
           (let [radius-update
                 (fn [shape]
                   (cond-> shape
                     (:rx shape)
                     (-> (assoc :r1 0 :r2 0 :r3 0 :r4 0)
                         (dissoc :rx :ry))))]
             (st/emit! (dch/update-shapes ids-with-children radius-update)))))

        on-radius-1-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value]
           (let [radius-update
                 (fn [shape]
                   (cond-> shape
                     (:r1 shape)
                     (-> (dissoc :r1 :r2 :r3 :r4)
                         (assoc :rx 0 :ry 0))

                     (or (:rx shape) (:r1 shape))
                     (assoc :rx value :ry value)))]

             (st/emit! (dch/update-shapes ids-with-children radius-update)))))

        on-radius-4-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value attr]
           (let [radius-update
                 (fn [shape]
                   (cond-> shape
                     (:rx shape)
                     (-> (dissoc :rx :rx)
                         (assoc :r1 0 :r2 0 :r3 0 :r4 0))

                     (attr shape)
                     (assoc attr value)))]

             (st/emit! (dch/update-shapes ids-with-children radius-update)))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)
        on-radius-r1-change #(on-radius-4-change % :r1)
        on-radius-r2-change #(on-radius-4-change % :r2)
        on-radius-r3-change #(on-radius-4-change % :r3)
        on-radius-r4-change #(on-radius-4-change % :r4)

        select-all #(-> % (dom/get-target) (.select))

        on-constraint-button-clicked
        (mf/use-callback
         (mf/deps [ids values])
         (fn [button]
           (fn [_]
             (let [constraints-h (get values :constraints-h :scale)
                   constraints-v (get values :constraints-v :scale)

                   [constraint new-value]
                   (case button
                     :top     (case constraints-v
                                :top [:constraints-v :scale]
                                :topbottom [:constraints-v :bottom]
                                :bottom [:constraints-v :topbottom]
                                [:constraints-v :top])
                     :bottom  (case constraints-v
                                :bottom [:constraints-v :scale]
                                :topbottom [:constraints-v :top]
                                :top [:constraints-v :topbottom]
                                [:constraints-v :bottom])
                     :left    (case constraints-h
                                :left [:constraints-h :scale]
                                :leftright [:constraints-h :right]
                                :right [:constraints-h :leftright]
                                [:constraints-h :left])
                     :right   (case constraints-h
                                :right [:constraints-h :scale]
                                :leftright [:constraints-h :left]
                                :left [:constraints-h :leftright]
                                [:constraints-h :right])
                     :centerv  (case constraints-v
                                 :center [:constraints-v :scale]
                                 [:constraints-v :center])
                     :centerh  (case constraints-h
                                 :center [:constraints-h :scale]
                                 [:constraints-h :center]))]
               (st/emit! (dch/update-shapes
                          ids
                          #(assoc % constraint new-value)))))))

        on-constraint-select-changed
        (mf/use-callback
         (mf/deps [ids values])
         (fn [constraint]
           (fn [event]
             (let [value (-> (dom/get-target-val event) (keyword))]
               (when-not (str/empty? value)
                 (st/emit! (dch/update-shapes
                            ids
                            #(assoc % constraint value))))))))

        on-fixed-scroll-clicked
        (mf/use-callback
         (mf/deps [ids values])
         (fn [_]
           (st/emit! (dch/update-shapes ids #(update % :fixed-scroll not)))))]
    [:*
     [:div.element-set
      [:div.element-set-content

       ;; WIDTH & HEIGHT
       (when (options :size)
         [:div.row-flex
          [:span.element-set-subtitle (tr "workspace.options.size")]
          [:div.input-element.width
           [:> numeric-input {:min 1
                              :no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-width-change
                              :value (attr->string :width values)}]]

          [:div.input-element.height
           [:> numeric-input {:min 1
                              :no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-height-change
                              :value (attr->string :height values)}]]

          [:div.lock-size {:class (dom/classnames
                                   :selected (true? proportion-lock)
                                   :disabled (= proportion-lock :multiple))
                           :on-click on-proportion-lock-change}
           (if proportion-lock
             i/lock
             i/unlock)]])

       ;; POSITION
       (when (options :position)
         [:div.row-flex
          [:span.element-set-subtitle (tr "workspace.options.position")]
          [:div.input-element.Xaxis
           [:> numeric-input {:no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-pos-x-change
                              :value (attr->string :x values)}]]
          [:div.input-element.Yaxis
           [:> numeric-input {:no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-pos-y-change
                              :value (attr->string :y values)}]]])

       ;; ROTATION
       (when (options :rotation)
         [:div.row-flex
          [:span.element-set-subtitle (tr "workspace.options.rotation")]
          [:div.input-element.degrees
           [:> numeric-input
            {:no-validate true
             :min 0
             :max 359
             :data-wrap true
             :placeholder "--"
             :on-click select-all
             :on-change on-rotation-change
             :value (attr->string :rotation values)}]]
          #_[:input.slidebar
             {:type "range"
              :min "0"
              :max "359"
              :step "10"
              :no-validate true
              :on-change on-rotation-change
              :value (attr->string :rotation values)}]])

       ;; RADIUS
       (let [radius-1? (some? (:rx values))
             radius-4? (some? (:r1 values))]
         (when (and (options :radius) (or radius-1? radius-4?))
           [:div.row-flex
            [:div.radius-options
             [:div.radius-icon.tooltip.tooltip-bottom
              {:class (dom/classnames
                       :selected
                       (and radius-1? (not radius-4?)))
               :alt (tr "workspace.options.radius.all-corners")
               :on-click on-switch-to-radius-1}
              i/radius-1]
             [:div.radius-icon.tooltip.tooltip-bottom
              {:class (dom/classnames
                       :selected
                       (and radius-4? (not radius-1?)))
               :alt (tr "workspace.options.radius.single-corners")
               :on-click on-switch-to-radius-4}
              i/radius-4]]
            (if radius-1?
              [:div.input-element.mini
               [:> numeric-input
                {:placeholder "--"
                 :min 0
                 :on-click select-all
                 :on-change on-radius-1-change
                 :value (attr->string :rx values)}]]

              [:*
               [:div.input-element.mini
                [:> numeric-input
                 {:placeholder "--"
                  :min 0
                  :on-click select-all
                  :on-change on-radius-r1-change
                  :value (attr->string :r1 values)}]]
               [:div.input-element.mini
                [:> numeric-input
                 {:placeholder "--"
                  :min 0
                  :on-click select-all
                  :on-change on-radius-r2-change
                  :value (attr->string :r2 values)}]]
               [:div.input-element.mini
                [:> numeric-input
                 {:placeholder "--"
                  :min 0
                  :on-click select-all
                  :on-change on-radius-r3-change
                  :value (attr->string :r3 values)}]]
               [:div.input-element.mini
                [:> numeric-input
                 {:placeholder "--"
                  :min 0
                  :on-click select-all
                  :on-change on-radius-r4-change
                  :value (attr->string :r4 values)}]]])]))]]

     ;; CONSTRAINTS
     (when in-frame?
       [:div.element-set
        [:div.element-set-title
         [:span (tr "workspace.options.constraints")]]

        [:div.element-set-content
         [:div.row-flex.align-top

          [:div.constraints-widget
           [:div.constraints-box]
           [:div.constraint-button.top
            {:class (dom/classnames :active (or (= constraints-v :top)
                                                (= constraints-v :topbottom)))
             :on-click (on-constraint-button-clicked :top)}]
           [:div.constraint-button.bottom
            {:class (dom/classnames :active (or (= constraints-v :bottom)
                                                (= constraints-v :topbottom)))
             :on-click (on-constraint-button-clicked :bottom)}]
           [:div.constraint-button.left
            {:class (dom/classnames :active (or (= constraints-h :left)
                                                (= constraints-h :leftright)))
             :on-click (on-constraint-button-clicked :left)}]
           [:div.constraint-button.right
            {:class (dom/classnames :active (or (= constraints-h :right)
                                                (= constraints-h :leftright)))
             :on-click (on-constraint-button-clicked :right)}]
           [:div.constraint-button.centerv
            {:class (dom/classnames :active (= constraints-v :center))
             :on-click (on-constraint-button-clicked :centerv)}]
           [:div.constraint-button.centerh
            {:class (dom/classnames :active (= constraints-h :center))
             :on-click (on-constraint-button-clicked :centerh)}]]

          [:div.constraints-form
           [:div.row-flex
            [:span.left-right i/full-screen]
            [:select.input-select {:on-change (on-constraint-select-changed :constraints-h)
                                   :value (d/name constraints-h "scale")}
             (when (= constraints-h :multiple)
               [:option {:value ""} (tr "settings.multiple")])
             [:option {:value "left"} (tr "workspace.options.constraints.left")]
             [:option {:value "right"} (tr "workspace.options.constraints.right")]
             [:option {:value "leftright"} (tr "workspace.options.constraints.leftright")]
             [:option {:value "center"} (tr "workspace.options.constraints.center")]
             [:option {:value "scale"} (tr "workspace.options.constraints.scale")]]]
           [:div.row-flex
            [:span.top-bottom i/full-screen]
            [:select.input-select {:on-change (on-constraint-select-changed :constraints-v)
                                   :value (d/name constraints-v "scale")}
             (when (= constraints-v :multiple)
               [:option {:value ""} (tr "settings.multiple")])
             [:option {:value "top"} (tr "workspace.options.constraints.top")]
             [:option {:value "bottom"} (tr "workspace.options.constraints.bottom")]
             [:option {:value "topbottom"} (tr "workspace.options.constraints.topbottom")]
             [:option {:value "center"} (tr "workspace.options.constraints.center")]
             [:option {:value "scale"} (tr "workspace.options.constraints.scale")]]]
           (when first-level?
             [:div.row-flex
              [:div.fix-when {:class (dom/classnames :active (:fixed-scroll values))
                              :on-click on-fixed-scroll-clicked}
               i/pin
               [:span (tr "workspace.options.constraints.fix-when-scrolling")]]])]]]])]))
