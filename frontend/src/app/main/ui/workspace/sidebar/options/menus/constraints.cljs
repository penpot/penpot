;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.constraints
  (:require
   [app.common.data :as d]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def constraint-attrs [:constraints-h
                       :constraints-v
                       :fixed-scroll
                       :parent-id
                       :frame-id])

(mf/defc constraints-menu
  [{:keys [ids values] :as props}]
  (let [old-shapes (deref (refs/objects-by-id ids))
        frames (map #(deref (refs/object-by-id (:frame-id %))) old-shapes)

        shapes (as-> old-shapes $
                 (map gsh/translate-to-frame $ frames))

        ;; FIXME: performance rect
        values (let [{:keys [x y]} (-> shapes first :points grc/points->rect)]
                 (cond-> values
                   (not= (:x values) :multiple) (assoc :x x)
                   (not= (:y values) :multiple) (assoc :y y)))

        values (let [{:keys [width height]} (-> shapes first :selrect)]
                 (cond-> values
                   (not= (:width values) :multiple) (assoc :width width)
                   (not= (:height values) :multiple) (assoc :height height)))

        in-frame? (and (some? ids)
                       (not= (:parent-id values) uuid/zero))

        first-level? (and in-frame?
                          (= (:parent-id values) (:frame-id values)))

        constraints-h (or (get values :constraints-h) (gsh/default-constraints-h values))
        constraints-v (or (get values :constraints-v) (gsh/default-constraints-v values))

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
               (if (:fixed-scroll values)
                 i/pin-fill
                 i/pin)
               [:span (tr "workspace.options.constraints.fix-when-scrolling")]]])]]]])))
