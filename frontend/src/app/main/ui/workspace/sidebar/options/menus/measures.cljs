;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.measures
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.shape.radius :as ctsr]
   [app.main.constants :refer [size-presets]]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [clojure.set :refer [rename-keys union]]
   [rumext.v2 :as mf]))

(def measure-attrs
  [:proportion-lock
   :width :height
   :x :y
   :ox :oy
   :rotation
   :rx :ry
   :r1 :r2 :r3 :r4
   :selrect
   :points
   :show-content
   :hide-in-viewer])

(def ^:private type->options
  {:bool    #{:size :position :rotation}
   :circle  #{:size :position :rotation}
   :frame   #{:presets :size :position :rotation :radius :clip-content :show-in-viewer}
   :group   #{:size :position :rotation}
   :image   #{:size :position :rotation :radius}
   :path    #{:size :position :rotation}
   :rect    #{:size :position :rotation :radius}
   :svg-raw #{:size :position :rotation}
   :text    #{:size :position :rotation}})

(defn select-measure-keys
  "Consider some shapes can be drawn from bottom to top or from left to right"
  [shape]
  (let [shape (cond
                (and (:flip-x shape) (:flip-y shape))
                (rename-keys shape {:r1 :r3 :r2 :r4 :r3 :r1 :r4 :r2})

                (:flip-x shape)
                (rename-keys shape {:r1 :r2 :r2 :r1 :r3 :r4 :r4 :r3})

                (:flip-y shape)
                (rename-keys shape {:r1 :r4 :r2 :r3 :r3 :r2 :r4 :r1})

                :else
                shape)]
    (select-keys shape measure-attrs)))

;; -- User/drawing coords
(mf/defc measures-menu
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [ids ids-with-children values type all-types shape]}]
  (let [options (if (= type :multiple)
                  (reduce #(union %1 %2) (map #(get type->options %) all-types))
                  (get type->options type))

        ids-with-children (or ids-with-children ids)

        old-shapes (if (= type :multiple)
                     (deref (refs/objects-by-id ids))
                     [shape])
        frames (map #(deref (refs/object-by-id (:frame-id %))) old-shapes)

        selection-parents-ref (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        selection-parents     (mf/deref selection-parents-ref)

        flex-child? (->> selection-parents (some ctl/flex-layout?))
        absolute? (ctl/layout-absolute? shape)
        flex-container? (ctl/flex-layout? shape)
        flex-auto-width? (ctl/auto-width? shape)
        flex-fill-width? (ctl/fill-width? shape)
        flex-auto-height? (ctl/auto-height? shape)
        flex-fill-height? (ctl/fill-height? shape)

        disabled-position-x? (and flex-child? (not absolute?))
        disabled-position-y? (and flex-child? (not absolute?))
        disabled-width-sizing? (and (or flex-child? flex-container?)
                                    (or flex-auto-width? flex-fill-width?)
                                    (not absolute?))
        disabled-height-sizing? (and (or flex-child? flex-container?)
                                     (or flex-auto-height? flex-fill-height?)
                                     (not absolute?))

        ;; To show interactively the measures while the user is manipulating
        ;; the shape with the mouse, generate a copy of the shapes applying
        ;; the transient transformations.
        shapes (as-> old-shapes $
                 (map gsh/translate-to-frame $ frames))

        ;; For rotated or stretched shapes, the origin point we show in the menu
        ;; is not the (:x :y) shape attribute, but the top left coordinate of the
        ;; wrapping rectangle.
        values (let [{:keys [x y]} (gsh/shapes->rect [(first shapes)])]
                 (cond-> values
                   (not= (:x values) :multiple) (assoc :x x)
                   (not= (:y values) :multiple) (assoc :y y)
                   ;; In case of multiple selection, the origin point has been already
                   ;; calculated and given in the fake :ox and :oy attributes. See
                   ;; common/src/app/common/attrs.cljc
                   (and (= (:x values) :multiple)
                        (some? (:ox values))) (assoc :x (:ox values))
                   (and (= (:y values) :multiple)
                        (some? (:oy values))) (assoc :y (:oy values))))

        ;; For :height and :width we take those in the :selrect attribute, because
        ;; not all shapes have an own :width and :height (e. g. paths). Here the
        ;; rotation is ignored (selrect always has the original size excluding
        ;; transforms).
        values (let [{:keys [width height]} (-> shapes first :selrect)]
                 (cond-> values
                   (not= (:width values) :multiple) (assoc :width width)
                   (not= (:height values) :multiple) (assoc :height height)))

        ;; The :rotation, however, does use the transforms.
        values (let [{:keys [rotation] :or {rotation 0}} (-> shapes first)]
                 (cond-> values
                   (not= (:rotation values) :multiple) (assoc :rotation rotation)))

        proportion-lock (:proportion-lock values)

        show-presets-dropdown? (mf/use-state false)

        radius-mode      (ctsr/radius-mode values)
        all-equal?       (ctsr/all-equal? values)
        radius-multi?    (mf/use-state nil)
        radius-input-ref (mf/use-ref nil)

        clip-content-ref (mf/use-ref nil)
        show-in-viewer-ref (mf/use-ref nil)

        on-preset-selected
        (fn [width height]
          (st/emit! (udw/update-dimensions ids :width width)
                    (udw/update-dimensions ids :height height)))

        on-orientation-clicked
        (fn [orientation]
          (st/emit! (udw/change-orientation ids orientation)))

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
             (st/emit! (udw/update-position (:id shape') {attr to})))))

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

        change-radius
        (mf/use-callback
         (mf/deps ids-with-children)
         (fn [update-fn]
           (dch/update-shapes ids-with-children
                              (fn [shape]
                                (if (ctsr/has-radius? shape)
                                  (update-fn shape)
                                  shape))
                              {:reg-objects? true
                               :attrs [:rx :ry :r1 :r2 :r3 :r4]})))

        on-switch-to-radius-1
        (mf/use-callback
         (mf/deps ids)
         (fn [_value]
           (if all-equal?
             (st/emit! (change-radius ctsr/switch-to-radius-1))
             (reset! radius-multi? true))))

        on-switch-to-radius-4
        (mf/use-callback
         (mf/deps ids)
         (fn [_value]
           (st/emit! (change-radius ctsr/switch-to-radius-4))
           (reset! radius-multi? false)))

        on-radius-1-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value]
           (st/emit! (change-radius #(ctsr/set-radius-1 % value)))))

        on-radius-multi-change
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value d/parse-integer)]
             (when (some? value)
               (st/emit! (change-radius ctsr/switch-to-radius-1)
                         (change-radius #(ctsr/set-radius-1 % value)))
               (reset! radius-multi? false)))))

        on-radius-4-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value attr]
           (st/emit! (change-radius #(ctsr/set-radius-4 % attr value)))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)
        on-radius-r1-change #(on-radius-4-change % :r1)
        on-radius-r2-change #(on-radius-4-change % :r2)
        on-radius-r3-change #(on-radius-4-change % :r3)
        on-radius-r4-change #(on-radius-4-change % :r4)

        on-change-clip-content
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (st/emit! (dch/update-shapes ids (fn [shape] (assoc shape :show-content (not value))))))))

        on-change-show-in-viewer
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)
                 undo-id (js/Symbol)]
             (do
               (st/emit! (dwu/start-undo-transaction undo-id)
                         (dch/update-shapes ids (fn [shape] (assoc shape :hide-in-viewer (not value)))))

               (when-not value
                 ;; when a frame is no longer shown in view mode, cannot have
                 ;; interactions that navigate to it.
                 (apply st/emit! (map #(dwi/remove-all-interactions-nav-to %) ids)))

               (st/emit! (dwu/commit-undo-transaction undo-id))))))

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
     [:div.element-set
      [:div.element-set-content

       ;; FRAME PRESETS
       (when (and (options :presets)
                  (or (nil? all-types) (= (count all-types) 1))) ;; Don't show presets if multi selected
         [:div.row-flex                                          ;; some frames and some non frames
          [:div.presets.custom-select.flex-grow {:class (when @show-presets-dropdown? "opened")
                                                 :on-click #(reset! show-presets-dropdown? true)}
           [:span (tr "workspace.options.size-presets")]
           [:span.dropdown-button i/arrow-down]
           [:& dropdown {:show @show-presets-dropdown?
                         :on-close #(reset! show-presets-dropdown? false)}
            [:ul.custom-select-dropdown
             (for [size-preset size-presets]
               (if-not (:width size-preset)
                 [:li.dropdown-label {:key (:name size-preset)}
                  [:span (:name size-preset)]]
                 [:li {:key (:name size-preset)
                       :on-click #(on-preset-selected (:width size-preset) (:height size-preset))}
                  (:name size-preset)
                  [:span (:width size-preset) " x " (:height size-preset)]]))]]]
          [:span.orientation-icon {:on-click #(on-orientation-clicked :vert)} i/size-vert]
          [:span.orientation-icon {:on-click #(on-orientation-clicked :horiz)} i/size-horiz]])

       ;; WIDTH & HEIGHT
       (when (options :size)
         [:div.row-flex
          [:span.element-set-subtitle (tr "workspace.options.size")]
          [:div.input-element.width {:title (tr "workspace.options.width")}
           [:> numeric-input {:min 0.01
                              :no-validate true
                              :placeholder "--"
                              :on-focus select-all
                              :on-change on-width-change
                              :disabled disabled-width-sizing?
                              :value (:width values)}]]

          [:div.input-element.height {:title (tr "workspace.options.height")}
           [:> numeric-input {:min 0.01
                              :no-validate true
                              :placeholder "--"
                              :on-focus select-all
                              :on-change on-height-change
                              :disabled disabled-height-sizing?
                              :value (:height values)}]]

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
                              :on-focus select-all
                              :on-change on-pos-x-change
                              :disabled disabled-position-x?
                              :value (:x values)}]]
          [:div.input-element.Yaxis {:title (tr "workspace.options.y")}
           [:> numeric-input {:no-validate true
                              :placeholder "--"
                              :on-focus select-all
                              :disabled disabled-position-y?
                              :on-change on-pos-y-change
                              :value (:y values)}]]])

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
             :on-focus select-all
             :on-change on-rotation-change
             :value (:rotation values)}]]])

       ;; RADIUS
       (when (options :radius)
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
               :on-focus select-all
               :on-change on-radius-1-change
               :value (:rx values)}]]

            @radius-multi?
            [:div.input-element.mini {:title (tr "workspace.options.radius")}
             [:input.input-text
              {:type "number"
               :placeholder "--"
               :min 0
               :on-focus select-all
               :on-change on-radius-multi-change
               :value ""}]]

            (= radius-mode :radius-4)
            [:*
             [:div.input-element.mini {:title (tr "workspace.options.radius")}
              [:> numeric-input
               {:placeholder "--"
                :min 0
                :on-focus select-all
                :on-change on-radius-r1-change
                :value (:r1 values)}]]

             [:div.input-element.mini {:title (tr "workspace.options.radius")}
              [:> numeric-input
               {:placeholder "--"
                :min 0
                :on-focus select-all
                :on-change on-radius-r2-change
                :value (:r2 values)}]]

             [:div.input-element.mini {:title (tr "workspace.options.radius")}
              [:> numeric-input
               {:placeholder "--"
                :min 0
                :on-focus select-all
                :on-change on-radius-r3-change
                :value (:r3 values)}]]

             [:div.input-element.mini {:title (tr "workspace.options.radius")}
              [:> numeric-input
               {:placeholder "--"
                :min 0
                :on-focus select-all
                :on-change on-radius-r4-change
                :value (:r4 values)}]]])])

       (when (options :clip-content)
         [:div.input-checkbox
          [:input {:type "checkbox"
                   :id "clip-content"
                   :ref clip-content-ref
                   :checked (not (:show-content values))
                   :on-change on-change-clip-content}]

          [:label {:for "clip-content"}
           (tr "workspace.options.clip-content")]])

       (when (options :show-in-viewer)
         [:div.input-checkbox
          [:input {:type "checkbox"
                   :id "show-in-viewer"
                   :ref show-in-viewer-ref
                   :checked (not (:hide-in-viewer values))
                   :on-change on-change-show-in-viewer}]

          [:label {:for "show-in-viewer"}
           (tr "workspace.options.show-in-viewer")]])

       ]]]))
