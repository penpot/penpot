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
   [app.main.data.workspace :as udw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.radius :refer [border-radius]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(def measure-attrs
  [:proportion-lock
   :width :height
   :x :y
   :rotation
   :rx :ry
   :r1 :r2 :r3 :r4
   :selrect])

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

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)

        select-all #(-> % (dom/get-target) (.select))]

    [:*
     [:div.element-set
      [:div.element-set-content

       ;; WIDTH & HEIGHT
       (when (options :size)
         [:div.row-flex
          [:span.element-set-subtitle (tr "workspace.options.size")]
          [:div.input-element.width {:title (tr "workspace.options.width")}
           [:> numeric-input {:min 1
                              :no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-width-change
                              :value (attr->string :width values)}]]

          [:div.input-element.height {:title (tr "workspace.options.height")}
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
          [:div.input-element.Xaxis {:title (tr "workspace.options.x")}
           [:> numeric-input {:no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-pos-x-change
                              :value (attr->string :x values)
                              :precision 2}]]
          [:div.input-element.Yaxis {:title (tr "workspace.options.y")}
           [:> numeric-input {:no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-pos-y-change
                              :value (attr->string :y values)
                              :precision 2}]]])

       ;; ROTATION
       (when (options :rotation)
         [:div.row-flex
          [:span.element-set-subtitle (tr "workspace.options.rotation")]
          [:div.input-element.degrees {:title (tr "workspace.options.rotation")}
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
       [:& border-radius {:options options :ids-with-children ids-with-children :values values :ids ids}]]]]))
