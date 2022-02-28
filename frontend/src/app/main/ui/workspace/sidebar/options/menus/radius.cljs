;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.radius
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as math]
   [app.common.spec.radius :as ctr]
   [app.main.data.workspace.changes :as dch]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))


(defn- attr->string [attr values]
  (let [value (attr values)]
    (if (= value :multiple)
      ""
      (str (-> value
               (d/coalesce 0)
               (math/precision 2))))))

(mf/defc border-radius
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


        radius-mode      (ctr/radius-mode values)
        all-equal?       (ctr/all-equal? values)
        radius-multi?    (mf/use-state nil)
        radius-input-ref (mf/use-ref nil)

        on-switch-to-radius-1
        (mf/use-callback
         (mf/deps ids)
         (fn [_value]
           (if all-equal?
             (st/emit! (dch/update-shapes ids-with-children ctr/switch-to-radius-1))
             (reset! radius-multi? true))))

        on-switch-to-radius-4
        (mf/use-callback
         (mf/deps ids)
         (fn [_value]
           (st/emit! (dch/update-shapes ids-with-children ctr/switch-to-radius-4))
           (reset! radius-multi? false)))

        on-radius-1-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value]
           (st/emit! (dch/update-shapes ids-with-children #(ctr/set-radius-1 % value)))))

        on-radius-multi-change
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value d/parse-integer)]
             (when (some? value)
               (st/emit! (dch/update-shapes ids-with-children ctr/switch-to-radius-1)
                         (dch/update-shapes ids-with-children #(ctr/set-radius-1 % value)))
               (reset! radius-multi? false)))))

        on-radius-4-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value attr]
           (st/emit! (dch/update-shapes ids-with-children #(ctr/set-radius-4 % attr value)))))

        on-radius-r1-change #(on-radius-4-change % :r1)
        on-radius-r2-change #(on-radius-4-change % :r2)
        on-radius-r3-change #(on-radius-4-change % :r3)
        on-radius-r4-change #(on-radius-4-change % :r4)

        select-all #(-> % (dom/get-target) (.select))]

    (mf/use-layout-effect
     (mf/deps radius-mode @radius-multi?)
     (fn []
       (when (and (= radius-mode :radius-1)
                  (= @radius-multi? false))
          ;; when going back from radius-multi to normal radius-1,
          ;; restore focus to the newly created numeric-input
         (let [radius-input (mf/ref-val radius-input-ref)]
           (dom/focus! radius-input)))))

    [:*
       ;; RADIUS
     (when (and (options :radius) (some? radius-mode))
       [:div.row-flex
        [:div.radius-options
         [:div.radius-icon.tooltip.tooltip-bottom
          {:class (dom/classnames
                   :selected (or (= radius-mode :radius-1) @radius-multi?))
           :alt (tr "workspace.options.radius.all-corners")
           :on-click on-switch-to-radius-1}
          i/radius-1]
         [:div.radius-icon.tooltip.tooltip-bottom
          {:class (dom/classnames
                   :selected (and (= radius-mode :radius-4) (not @radius-multi?)))
           :alt (tr "workspace.options.radius.single-corners")
           :on-click on-switch-to-radius-4}
          i/radius-4]]

        (cond
          (= radius-mode :radius-1)
          [:div.input-element.mini {:title (tr "workspace.options.radius")}
           [:> numeric-input
            {:placeholder "--"
             :ref radius-input-ref
             :min 0
             :on-click select-all
             :on-change on-radius-1-change
             :value (attr->string :rx values)}]]

          @radius-multi?
          [:div.input-element.mini {:title (tr "workspace.options.radius")}
           [:input.input-text
            {:type "number"
             :placeholder "--"
             :on-click select-all
             :on-change on-radius-multi-change
             :value ""}]]

          (= radius-mode :radius-4)
          [:*
           [:div.input-element.mini {:title (tr "workspace.options.radius")}
            [:> numeric-input
             {:placeholder "--"
              :min 0
              :on-click select-all
              :on-change on-radius-r1-change
              :value (attr->string :r1 values)}]]

           [:div.input-element.mini {:title (tr "workspace.options.radius")}
            [:> numeric-input
             {:placeholder "--"
              :min 0
              :on-click select-all
              :on-change on-radius-r2-change
              :value (attr->string :r2 values)}]]

           [:div.input-element.mini {:title (tr "workspace.options.radius")}
            [:> numeric-input
             {:placeholder "--"
              :min 0
              :on-click select-all
              :on-change on-radius-r3-change
              :value (attr->string :r3 values)}]]

           [:div.input-element.mini {:title (tr "workspace.options.radius")}
            [:> numeric-input
             {:placeholder "--"
              :min 0
              :on-click select-all
              :on-change on-radius-r4-change
              :value (attr->string :r4 values)}]]])])]))
