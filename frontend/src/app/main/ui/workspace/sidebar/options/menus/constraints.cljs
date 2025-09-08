;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.constraints
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.icons :as deprecated-icon]
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
  (let [state*          (mf/use-state true)
        open?           (deref state*)

        toggle-content  (mf/use-fn #(swap! state* not))

        old-shapes      (deref (refs/objects-by-id ids))
        frames          (map #(deref (refs/object-by-id (:frame-id %))) old-shapes)

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
        (mf/use-fn
         (mf/deps ids values)
         (fn [event]
           (let [button  (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (keyword))
                 constraints-h (get values :constraints-h :scale)
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
                               [:constraints-h :center])
                   nil ())]

             (st/emit! (dwsh/update-shapes
                        ids
                        #(assoc % constraint new-value))))))

        on-constraint-h-select-changed
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (when-not (str/empty? value)
             (st/emit! (dwsh/update-shapes
                        ids
                        #(assoc % :constraints-h (keyword value)))))))

        on-constraint-v-select-changed
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (when-not (str/empty? value)
             (st/emit! (dwsh/update-shapes
                        ids
                        #(assoc % :constraints-v (keyword value)))))))

        on-fixed-scroll-clicked
        (mf/use-fn
         (mf/deps ids)
         (fn [_]
           (st/emit! (dwsh/update-shapes ids #(update % :fixed-scroll not)))))

        options-h
        (mf/with-memo [constraints-h]
          (d/concat-vec
           (when (= constraints-h :multiple)
             [{:value "" :label (tr "settings.multiple")}])
           [{:value "left" :label (tr "workspace.options.constraints.left")}
            {:value "right" :label (tr "workspace.options.constraints.right")}
            {:value "leftright" :label (tr "workspace.options.constraints.leftright")}
            {:value "center" :label (tr "workspace.options.constraints.center")}
            {:value "scale" :label (tr "workspace.options.constraints.scale")}]))

        options-v
        (mf/with-memo [constraints-v]
          (d/concat-vec
           (when (= constraints-v :multiple)
             [{:value "" :label (tr "settings.multiple")}])
           [{:value "top" :label (tr "workspace.options.constraints.top")}
            {:value "bottom" :label (tr "workspace.options.constraints.bottom")}
            {:value "topbottom" :label (tr "workspace.options.constraints.topbottom")}
            {:value "center" :label (tr "workspace.options.constraints.center")}
            {:value "scale" :label (tr "workspace.options.constraints.scale")}]))]


    ;; CONSTRAINTS
    (when in-frame?
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable  true
                        :collapsed    (not open?)
                        :on-collapsed toggle-content
                        :title        (tr "workspace.options.constraints")}]]
       (when open?
         [:div {:class (stl/css :element-set-content)}
          [:div {:class (stl/css :constraints-widget)}
           [:div {:class (stl/css :constraints-top)}
            [:button {:class (stl/css-case :constraint-btn true
                                           :active (or (= constraints-v :top)
                                                       (= constraints-v :topbottom)))
                      :data-value "top"
                      :on-click on-constraint-button-clicked}
             [:span {:class (stl/css :resalted-area)}]]]
           [:div {:class (stl/css :constraints-left)}
            [:button {:class (stl/css-case :constraint-btn true
                                           :constraint-btn-rotated true
                                           :active (or (= constraints-h :left)
                                                       (= constraints-h :leftright)))
                      :data-value "left"
                      :on-click on-constraint-button-clicked}
             [:span {:class (stl/css :resalted-area)}]]]
           [:div {:class (stl/css :constraints-center)}
            [:button {:class (stl/css-case :constraint-btn true
                                           :active (= constraints-v :center))
                      :data-value "centerv"
                      :on-click on-constraint-button-clicked}
             [:span {:class (stl/css :resalted-area)}]]
            [:button {:class (stl/css-case :constraint-btn-special true
                                           :constraint-btn-rotated true
                                           :active (= constraints-h :center))
                      :data-value "centerh"
                      :on-click on-constraint-button-clicked}
             [:span {:class (stl/css :resalted-area)}]]]
           [:div {:class (stl/css :constraints-right)}
            [:button {:class (stl/css-case :constraint-btn true
                                           :constraint-btn-rotated true
                                           :active (or (= constraints-h :right)
                                                       (= constraints-h :leftright)))
                      :data-value "right"
                      :on-click on-constraint-button-clicked}
             [:span {:class (stl/css :resalted-area)}]]]
           [:div {:class (stl/css :constraints-bottom)}
            [:button {:class (stl/css-case :constraint-btn true
                                           :active (or (= constraints-v :bottom)
                                                       (= constraints-v :topbottom)))
                      :data-value "bottom"
                      :on-click on-constraint-button-clicked}
             [:span {:class (stl/css :resalted-area)}]]]]
          [:div {:class (stl/css :contraints-selects)}
           [:div {:class (stl/css :horizontal-select) :data-testid "constraint-h-select"}
            [:& select
             {:default-value (if (not= constraints-h :multiple) (d/nilv (d/name constraints-h) "scale") "")
              :options options-h
              :on-change on-constraint-h-select-changed}]]
           [:div {:class (stl/css :vertical-select) :data-testid "constraint-v-select"}
            [:& select
             {:default-value (if (not= constraints-v :multiple) (d/nilv (d/name constraints-v) "scale") "")
              :options options-v
              :on-change on-constraint-v-select-changed}]]
           (when first-level?
             [:div {:class (stl/css :checkbox)}

              [:label {:for "fixed-on-scroll"
                       :class (stl/css-case :checked (:fixed-scroll values))}
               [:span {:class (stl/css-case :check-mark true
                                            :checked (:fixed-scroll values))}
                (when (:fixed-scroll values)
                  deprecated-icon/status-tick)]
               (tr "workspace.options.constraints.fix-when-scrolling")
               [:input {:type "checkbox"
                        :id "fixed-on-scroll"
                        :checked (:fixed-scroll values)
                        :on-change on-fixed-scroll-clicked}]]])]])])))
