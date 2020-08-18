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
        shapes (map gsh/transform-shape frames old-shapes)

        values (cond-> values
                 (not= (:x values) :multiple) (assoc :x (:x (:selrect (first shapes))))
                 (not= (:y values) :multiple) (assoc :y (:y (:selrect (first shapes)))))

        proportion-lock (:proportion-lock values)

        on-size-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-dimensions ids attr value))))

        on-proportion-lock-change
        (fn [event]
          (let [new-lock (if (= proportion-lock :multiple) true (not proportion-lock))]
            (run! #(st/emit! (udw/set-shape-proportion-lock % new-lock)) ids)))

        do-position-change
        (fn [shape' frame' value attr]
          (let [from (-> shape' :selrect attr)
                to (+ value (attr frame'))
                target (+ (attr shape') (- to from))]
            (st/emit! (udw/update-position (:id shape') {attr target}))))

        on-position-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (when value
              (doall (map #(do-position-change %1 %2 value attr) shapes frames)))))

        do-rotation-change
        (fn [shape' old-shape' value]
          (st/emit! (udw/set-rotation (- value (:rotation shape')) [old-shape'])
                    (udw/apply-modifiers #{(:id shape')})))

        on-rotation-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (doall (map #(do-rotation-change %1 %2 value) shapes old-shapes))))

        on-radius-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (dwc/update-shapes
                        ids-with-children
                        #(if (:rx %)
                           (assoc % :rx value :ry value)
                           %)))))

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
         [:div.lock-size {:class (classnames
                                   :selected (true? proportion-lock)
                                   :disabled (= proportion-lock :multiple))
                          :on-click on-proportion-lock-change}
          (if proportion-lock
            i/lock
            i/unlock)]
         [:div.input-element.width
          [:input.input-text {:type "number"
                              :min "0"
                              :no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-width-change
                              :value (attr->string :width values)}]]


         [:div.input-element.height
          [:input.input-text {:type "number"
                              :min "0"
                              :no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-height-change
                              :value (attr->string :height values)}]]])

      ;; POSITION
      (when (options :position)
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.position")]
         [:div.input-element.Xaxis
          [:input.input-text {:type "number"
                              :no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-pos-x-change
                              :value (attr->string :x values)}]]
         [:div.input-element.Yaxis
          [:input.input-text {:type "number"
                              :no-validate true
                              :placeholder "--"
                              :on-click select-all
                              :on-change on-pos-y-change
                              :value (attr->string :y values)}]]])

      ;; ROTATION
      (when (options :rotation)
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.rotation")]
         [:div.input-element.degrees
          [:input.input-text
           {:type "number"
            :no-validate true
            :min "0"
            :max "359"
            :placeholder "--"
            :on-click select-all
            :on-change on-rotation-change
            :value (attr->string :rotation values)}]]
         [:input.slidebar
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
          [:input.input-text
           {:type "number"
            :placeholder "--"
            :on-click select-all
            :on-change on-radius-change
            :value (attr->string :rx values)}]]
         [:div.input-element]])]]))
