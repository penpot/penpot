;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.measures
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.common.data :as d]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.shapes :as gsh]
   [uxbox.util.geom.point :as gpt]
   [uxbox.main.data.workspace :as udw]
   [uxbox.util.math :as math]
   [uxbox.util.i18n :refer [t] :as i18n]))

;; -- User/drawing coords

(defn user-coords-vector
  [shape]
  (let [{sel-x :x sel-y :y :as selrect}
        (-> shape
            gsh/shape->path
            (gsh/center-transform (:transform shape))
            gsh/shape->rect-shape)
        {rec-x :x rec-y :y} (-> shape gsh/shape->rect-shape)

        dx (- rec-x sel-x)
        dy (- rec-y sel-y)]
    (-> (gpt/point dx dy)
        (gpt/round 2))))

(defn user->draw
  [{:keys [x y width height] :as shape}]
  (let [dv (user-coords-vector shape)]
    (-> shape
        (gsh/move dv))))

(defn draw->user
  [{:keys [x y width height] :as shape}]
  (let [dv (user-coords-vector shape)]
    (-> shape
        (gsh/move (gpt/negate dv)))))

(mf/defc measures-menu
  [{:keys [shape options] :as props}]
  (let [options (or options #{:size :position :rotation :radius})
        locale (i18n/use-locale)
        frame (deref (refs/object-by-id (:frame-id shape)))
        old-shape shape
        shape (->> shape
                   (gsh/transform-shape frame)
                   (draw->user))

        on-size-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-rect-dimensions (:id shape) attr value))))

        on-proportion-lock-change
        (fn [event]
          (st/emit! (udw/toggle-shape-proportion-lock (:id shape))))

        on-position-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))
                new-shape (-> shape
                              (assoc attr value)
                              (gsh/translate-from-frame frame)
                              (user->draw))]
            (st/emit! (udw/update-position (:id shape) (select-keys new-shape [attr])))))

        on-rotation-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/set-rotation (- value (:rotation shape)) [old-shape])
                      (udw/apply-modifiers #{(:id shape)}))))

        on-radius-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-shape (:id shape) {:rx value :ry value}))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)]

    [:div.element-set
     [:div.element-set-content

      ;; WIDTH & HEIGHT
      (when (options :size)
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.size")]
         [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                          :on-click on-proportion-lock-change}
          (if (:proportion-lock shape)
            i/lock
            i/unlock)]
         [:div.input-element.width
          [:input.input-text {:type "number"
                              :min "0"
                              :no-validate true
                              :on-change on-width-change
                              :value (str (-> (:width shape)
                                              (d/coalesce 0)
                                              (math/round)))}]]


         [:div.input-element.height
          [:input.input-text {:type "number"
                              :min "0"
                              :no-validate true
                              :on-change on-height-change
                              :value (str (-> (:height shape)
                                              (d/coalesce 0)
                                              (math/round)))}]]])

      ;; POSITION
      (when (options :position)
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.position")]
         [:div.input-element.Xaxis
          [:input.input-text {:placeholder "x"
                              :type "number"
                              :no-validate true
                              :on-change on-pos-x-change
                              :value (:x shape)}]]
         [:div.input-element.Yaxis
          [:input.input-text {:placeholder "y"
                              :type "number"
                              :no-validate true
                              :on-change on-pos-y-change
                              :value (:y shape)}]]])

      (when (options :rotation)
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.rotation")]
         [:div.input-element.degrees
          [:input.input-text
           {:placeholder ""
            :type "number"
            :no-validate true
            :min "0"
            :max "359"
            :on-change on-rotation-change
            :value (str (-> (:rotation shape)
                            (d/coalesce 0)
                            (math/round)))}]]
         [:input.slidebar
          {:type "range"
           :min "0"
           :max "359"
           :step "10"
           :no-validate true
           :on-change on-rotation-change
           :value (str (-> (:rotation shape)
                           (d/coalesce 0)
                           (math/round)))}]])

      (when (options :radius)
        [:div.row-flex
         [:span.element-set-subtitle (t locale "workspace.options.radius")]
         [:div.input-element.pixels
          [:input.input-text
           {:placeholder "rx"
            :type "number"
            :on-change on-radius-change
            :value (str (-> (:rx shape)
                            (d/coalesce 0)
                            (math/round)))}]]
         [:div.input-element]])]]))
