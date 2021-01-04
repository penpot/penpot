;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.measures
  (:require
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.data :refer [classnames]]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.point :as gpt]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.common :as dwc]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.common.math :as math]
   [app.util.i18n :refer [t] :as i18n]))

(def measure-attrs [:proportion-lock :width :height :x :y :rotation :rx :ry :selrect])

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
        locale (i18n/use-locale)

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

        on-size-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value attr]
           (st/emit! (udw/update-dimensions ids attr value))))

        on-proportion-lock-change
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
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

        on-radius-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value]
           (let [radius-update
                 (fn [shape]
                   (cond-> shape
                     (:rx shape) (assoc :rx value :ry value)))]
             (st/emit! (dwc/update-shapes ids-with-children radius-update)))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)
        select-all #(-> % (dom/get-target) (.select))]

    [:div.element-set
     [:div.element-set-content

      ;; WIDTH & HEIGHT
      (when (options :size)
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.size")]
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

         [:div.lock-size {:class (classnames
                                   :selected (true? proportion-lock)
                                   :disabled (= proportion-lock :multiple))
                          :on-click on-proportion-lock-change}
          (if proportion-lock
            i/lock
            i/unlock)]])

      ;; POSITION
      (when (options :position)
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.position")]
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
         [:span.element-set-subtitle (t locale "workspace.options.rotation")]
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
      (when (and (options :radius) (not (nil? (:rx values))))
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.radius")]
         [:div.input-element.pixels
          [:> numeric-input
           {:placeholder "--"
            :min 0
            :on-click select-all
            :on-change on-radius-change
            :value (attr->string :rx values)}]]
         [:div.input-element]])]]))
