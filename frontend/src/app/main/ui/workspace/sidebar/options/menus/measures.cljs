;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.measures
  (:require-macros [app.main.style :as stl])
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
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.hooks :as hooks]
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

(def ^:private clip-content-icon (i/icon-xref :clip-content (stl/css :checkbox-button)))
(def ^:private play-icon (i/icon-xref :play (stl/css :checkbox-button)))
(def ^:private locked-icon (i/icon-xref :detach (stl/css :lock-ratio-icon)))
(def ^:private unlocked-icon (i/icon-xref :detached (stl/css :lock-ratio-icon)))

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

        ids (hooks/use-equal-memo ids)

        selection-parents-ref (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        selection-parents     (mf/deref selection-parents-ref)

        flex-child?       (->> selection-parents (some ctl/flex-layout?))
        absolute?         (ctl/item-absolute? shape)
        flex-container?   (ctl/flex-layout? shape)
        flex-auto-width?  (ctl/auto-width? shape)
        flex-fill-width?  (ctl/fill-width? shape)
        flex-auto-height? (ctl/auto-height? shape)
        flex-fill-height? (ctl/fill-height? shape)

        disabled-position-x?   (and flex-child? (not absolute?))
        disabled-position-y?   (and flex-child? (not absolute?))
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

        proportion-lock  (:proportion-lock values)



        radius-mode      (ctsr/radius-mode values)
        all-equal?       (ctsr/all-equal? values)
        radius-multi?    (mf/use-state nil)
        radius-input-ref (mf/use-ref nil)

        clip-content-ref (mf/use-ref nil)
        show-in-viewer-ref (mf/use-ref nil)

        ;; PRESETS
        preset-state*         (mf/use-state false)
        show-presets-dropdown? (deref preset-state*)

        open-presets
        (mf/use-fn
         (mf/deps show-presets-dropdown?)
         (fn []
           (reset! preset-state* true)))

        close-presets
        (mf/use-fn
         (mf/deps show-presets-dropdown?)
         (fn []
           (reset! preset-state* false)))

        on-preset-selected
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [width (-> (dom/get-current-target event)
                           (dom/get-data "width")
                           (d/read-string))
                 height (-> (dom/get-current-target event)
                            (dom/get-data "height")
                            (d/read-string))]
             (st/emit! (udw/update-dimensions ids :width width)
                       (udw/update-dimensions ids :height height)))))

        ;; ORIENTATION

        orientation (when (= type :frame)
                      (cond (> (:width values) (:height values))
                            :horiz
                            :else
                            :vert))

        on-orientation-change
        (mf/use-fn
         (mf/deps ids)
         (fn [orientation]
           (st/emit! (udw/change-orientation ids (keyword orientation)))))

        ;; SIZE AND PROPORTION LOCK

        on-size-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value attr]
           (st/emit! (udw/trigger-bounding-box-cloaking ids)
                     (udw/update-dimensions ids attr value))))

        on-proportion-lock-change
        (mf/use-fn
         (mf/deps ids proportion-lock)
         (fn [_]
           (let [new-lock (if (= proportion-lock :multiple) true (not proportion-lock))]
             (run! #(st/emit! (udw/set-shape-proportion-lock % new-lock)) ids))))

        ;; POSITION

        do-position-change
        (mf/use-fn
         (mf/deps ids)
         (fn [shape' frame' value attr]
           (let [to (+ value (attr frame'))]
             (st/emit! (udw/update-position (:id shape') {attr to})))))

        on-position-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value attr]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (doall (map #(do-position-change %1 %2 value attr) shapes frames))))

        ;; ROTATION

        on-rotation-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (udw/trigger-bounding-box-cloaking ids)
                     (udw/increase-rotation ids value))))

        ;; RADIUS

        change-radius
        (mf/use-fn
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
        (mf/use-fn
         (mf/deps ids change-radius)
         (fn [_value]
           (if all-equal?
             (st/emit! (change-radius ctsr/switch-to-radius-1))
             (reset! radius-multi? true))))

        on-switch-to-radius-4
        (mf/use-fn
         (mf/deps ids change-radius)
         (fn [_value]
           (st/emit! (change-radius ctsr/switch-to-radius-4))
           (reset! radius-multi? false)))

        toggle-radius-mode
        (mf/use-fn
         (mf/deps radius-mode)
         (fn []
           (if (= :radius-1 radius-mode)
             (on-switch-to-radius-4)
             (on-switch-to-radius-1))))

        on-radius-1-change
        (mf/use-fn
         (mf/deps ids change-radius)
         (fn [value]
           (st/emit! (change-radius #(ctsr/set-radius-1 % value)))))

        on-radius-multi-change
        (mf/use-fn
         (mf/deps ids change-radius)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value d/parse-integer)]
             (when (some? value)
               (st/emit! (change-radius ctsr/switch-to-radius-1)
                         (change-radius #(ctsr/set-radius-1 % value)))
               (reset! radius-multi? false)))))

        on-radius-4-change
        (mf/use-fn
         (mf/deps ids change-radius)
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

        ;; CLIP CONTENT AND SHOW IN VIEWER
        on-change-clip-content
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (st/emit! (dch/update-shapes ids (fn [shape] (assoc shape :show-content (not value))))))))

        on-change-show-in-viewer
        (mf/use-fn
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

               (st/emit! (dwu/commit-undo-transaction undo-id))))))]

    (mf/use-layout-effect
     (mf/deps radius-mode @radius-multi?)
     (fn []
       (when (and (= radius-mode :radius-1)
                  (= @radius-multi? false))
         ;; when going back from radius-multi to normal radius-1,
         ;; restore focus to the newly created numeric-input
         (let [radius-input (mf/ref-val radius-input-ref)]
           (dom/focus! radius-input)))))
    [:div {:class (stl/css :element-set)}
     (when (and (options :presets)
                (or (nil? all-types) (= (count all-types) 1)))
       [:div {:class (stl/css :presets)}
        [:div {:class (stl/css-case  :presets-wrapper true
                                     :opened show-presets-dropdown?)
               :on-click open-presets}
         [:span {:class (stl/css :select-name)} (tr "workspace.options.size-presets")]
         [:span {:class (stl/css :collapsed-icon)} i/arrow]

         [:& dropdown {:show show-presets-dropdown?
                       :on-close close-presets}
          [:ul {:class (stl/css :custom-select-dropdown)}
           (for [size-preset size-presets]
             (if-not (:width size-preset)
               [:li {:key (:name size-preset)
                     :class (stl/css-case :dropdown-element true
                                          :disabled true)}
                [:span {:class (stl/css :preset-name)} (:name size-preset)]]

               (let [preset-match (and (= (:width size-preset) (d/parse-integer (:width values) 0))
                                       (= (:height size-preset) (d/parse-integer (:height values) 0)))]
                 [:li {:key (:name size-preset)
                       :class (stl/css-case :dropdown-element true
                                            :match preset-match)
                       :data-width (str (:width size-preset))
                       :data-height (str (:height size-preset))
                       :on-click on-preset-selected}
                  [:div {:class (stl/css :name-wrapper)}
                   [:span {:class (stl/css :preset-name)} (:name size-preset)]
                   [:span {:class (stl/css :preset-size)} (:width size-preset) " x " (:height size-preset)]]
                  (when preset-match
                    [:span {:class (stl/css :check-icon)} i/tick])])))]]]

        [:& radio-buttons {:selected (or (d/name orientation) "")
                           :on-change on-orientation-change
                           :name "frame-otientation"}
         [:& radio-button {:icon i/size-vertical
                           :value "vert"
                           :id "size-vertical"}]
         [:& radio-button {:icon i/size-horizontal
                           :value "horiz"
                           :id "size-horizontal"}]]])
     (when (options :size)
       [:div {:class (stl/css :size)}
        [:div {:class (stl/css-case :width true
                                    :disabled disabled-width-sizing?)
               :title (tr "workspace.options.width")}
         [:span {:class (stl/css :icon-text)} "W"]
         [:> numeric-input* {:min 0.01
                             :no-validate true
                             :placeholder (if (= :multiple (:width values)) (tr "settings.multiple") "--")
                             :on-change on-width-change
                             :disabled disabled-width-sizing?
                             :className (stl/css :numeric-input)
                             :value (:width values)}]]
        [:div {:class (stl/css-case :height true
                                    :disabled disabled-height-sizing?)
               :title (tr "workspace.options.height")}
         [:span {:class (stl/css :icon-text)} "H"]
         [:> numeric-input* {:min 0.01
                             :no-validate true
                             :placeholder (if (= :multiple (:height values)) (tr "settings.multiple") "--")
                             :on-change on-height-change
                             :disabled disabled-height-sizing?
                             :className (stl/css :numeric-input)
                             :value (:height values)}]]
        [:button {:class (stl/css-case
                          :lock-size-btn true
                          :selected (true? proportion-lock)
                          :disabled (= proportion-lock :multiple))
                  :on-click on-proportion-lock-change}
         (if proportion-lock
           locked-icon
           unlocked-icon)]])
     (when (options :position)
       [:div {:class (stl/css :position)}
        [:div {:class (stl/css-case :x-position true
                                    :disabled disabled-position-x?)
               :title (tr "workspace.options.x")}
         [:span {:class (stl/css :icon-text)} "X"]
         [:> numeric-input* {:no-validate true
                             :placeholder (if (= :multiple (:x values)) (tr "settings.multiple") "--")
                             :on-change on-pos-x-change
                             :disabled disabled-position-x?
                             :className (stl/css :numeric-input)
                             :value (:x values)}]]

        [:div {:class (stl/css-case :y-position true
                                    :disabled disabled-position-y?)
               :title (tr "workspace.options.y")}
         [:span {:class (stl/css :icon-text)} "Y"]
         [:> numeric-input* {:no-validate true
                             :placeholder (if (= :multiple (:y values)) (tr "settings.multiple") "--")
                             :disabled disabled-position-y?
                             :on-change on-pos-y-change
                             :className (stl/css :numeric-input)
                             :value (:y values)}]]])
     (when (or (options :rotation) (options :radius))
       [:div {:class (stl/css :rotation-radius)}

        (when (options :rotation)
          [:div {:class (stl/css :rotation)
                 :title (tr "workspace.options.rotation")}
           [:span {:class (stl/css :icon)}  i/rotation]
           [:> numeric-input*
            {:no-validate true
             :min -359
             :max 359
             :data-wrap true
             :placeholder (if (= :multiple (:rotation values)) (tr "settings.multiple") "--")
             :on-change on-rotation-change
             :className (stl/css :numeric-input)
             :value (:rotation values)}]])

        (when (options :radius)
          [:div {:class (stl/css :radius)}
           [:div {:class (stl/css :radius-inputs)}
            (cond
              (= radius-mode :radius-1)
              [:div {:class (stl/css :radius-1)
                     :title (tr "workspace.options.radius")}
               [:span {:class (stl/css :icon)}  i/corner-radius]
               [:> numeric-input*
                {:placeholder (if (= :multiple (:rx values)) (tr "settings.multiple") "--")
                 :ref radius-input-ref
                 :min 0
                 :on-change on-radius-1-change
                 :className (stl/css :numeric-input)
                 :value (:rx values)}]]

              @radius-multi?
              [:div {:class (stl/css :radius-1)
                     :title (tr "workspace.options.radius")}
               [:span {:class (stl/css :icon)}  i/corner-radius]
               [:input.input-text
                {:type "number"
                 :placeholder "Mixed"
                 :min 0
                 :on-change on-radius-multi-change
                 :className (stl/css :numeric-input)
                 :value (if all-equal? (:rx values) nil)}]]


              (= radius-mode :radius-4)
              [:div {:class (stl/css :radius-4)}
               [:div {:class (stl/css :small-input)
                      :title (tr "workspace.options.radius-top-left")}
                [:> numeric-input*
                 {:placeholder "--"
                  :min 0
                  :on-change on-radius-r1-change
                  :className (stl/css :numeric-input)
                  :value (:r1 values)}]]

               [:div {:class (stl/css :small-input)
                      :title (tr "workspace.options.radius-top-right")}
                [:> numeric-input*
                 {:placeholder "--"
                  :min 0
                  :on-change on-radius-r2-change
                  :className (stl/css :numeric-input)
                  :value (:r2 values)}]]

               [:div {:class (stl/css :small-input)
                      :title (tr "workspace.options.radius-bottom-left")}
                [:> numeric-input*
                 {:placeholder "--"
                  :min 0
                  :on-change on-radius-r4-change
                  :className (stl/css :numeric-input)
                  :value (:r4 values)}]]

               [:div {:class (stl/css :small-input)
                      :title (tr "workspace.options.radius-bottom-right")}
                [:> numeric-input*
                 {:placeholder "--"
                  :min 0
                  :on-change on-radius-r3-change
                  :className (stl/css :numeric-input)
                  :value (:r3 values)}]]])]
           [:button {:class (stl/css-case :radius-mode true
                                          :selected (= radius-mode :radius-4))
                     :title (if (= radius-mode :radius-4)
                              (tr "workspace.options.radius.all-corners")
                              (tr "workspace.options.radius.single-corners"))
                     :on-click toggle-radius-mode}
            i/corner-radius]])])
     (when (or (options :clip-content) (options :show-in-viewer))
       [:div {:class (stl/css :clip-show)}
        (when (options :clip-content)
          [:div {:class (stl/css :clip-content)}
           [:input {:type "checkbox"
                    :id "clip-content"
                    :ref clip-content-ref
                    :class (stl/css :clip-content-input)
                    :checked (not (:show-content values))
                    :on-change on-change-clip-content}]

           [:label {:for "clip-content"
                    :title (tr "workspace.options.clip-content")
                    :class (stl/css-case  :clip-content-label true
                                          :selected (not (:show-content values)))}
            clip-content-icon]])
        (when (options :show-in-viewer)
          [:div {:class (stl/css :show-in-viewer)}
           [:input {:type "checkbox"
                    :id "show-in-viewer"
                    :ref show-in-viewer-ref
                    :class (stl/css :clip-content-input)
                    :checked (not (:hide-in-viewer values))
                    :on-change on-change-show-in-viewer}]

           [:label {:for "show-in-viewer"
                    :title (tr "workspace.options.show-in-viewer")
                    :class (stl/css-case  :clip-content-label true
                                          :selected (not (:hide-in-viewer values)))}
            [:span {:class (stl/css :icon)}
             play-icon]]])])]))
