;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.measures
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
   [app.main.data.workspace.changes :as dch]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.common.math :as math]
   [app.util.i18n :refer [t] :as i18n]))

(def measure-attrs [:proportion-lock
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

        on-switch-to-radius-1
        (mf/use-callback
         (mf/deps ids)
         (fn [value]
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
         (fn [value]
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
      (let [radius-1? (some? (:rx values))
            radius-4? (some? (:r1 values))]
        (when (and (options :radius) (or radius-1? radius-4?))
          [:div.row-flex
           [:div.radius-options
             [:div.radius-icon.tooltip.tooltip-bottom
              {:class (classnames
                        :selected
                        (and radius-1? (not radius-4?)))
               :alt (t locale "workspace.options.radius.all-corners")
               :on-click on-switch-to-radius-1}
              i/radius-1]
             [:div.radius-icon.tooltip.tooltip-bottom
              {:class (classnames
                        :selected
                        (and radius-4? (not radius-1?)))
               :alt (t locale "workspace.options.radius.single-corners")
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
                  :value (attr->string :r4 values)}]]])
           ]))]]))
