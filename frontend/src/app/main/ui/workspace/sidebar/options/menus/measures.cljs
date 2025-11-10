;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.measures
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.token :as tk]
   [app.main.constants :refer [size-presets]]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.options.menus.border-radius :refer  [border-radius-menu*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [clojure.set :as set]
   [rumext.v2 :as mf]))

(def measure-attrs
  [:proportion-lock
   :width :height
   :x :y
   :ox :oy
   :rotation
   :r1 :r2 :r3 :r4
   :selrect
   :points
   :show-content
   :hide-in-viewer])

(def ^:private generic-options
  #{:size :position :rotation})

(def ^:private rect-options
  #{:size :position :rotation :radius})

(def ^:private frame-options
  #{:presets :size :position :rotation :radius :clip-content :show-in-viewer})

(defn- type->options
  [type]
  (case type
    :bool    generic-options
    :circle  generic-options
    :frame   frame-options
    :group   generic-options
    :path    generic-options
    :rect    rect-options
    :svg-raw generic-options
    :text    generic-options))

(defn select-measure-keys
  "Consider some shapes can be drawn from bottom to top or from left to right"
  [shape]
  (let [flip-x (get shape :flip-x)
        flip-y (get shape :flip-y)

        shape  (cond
                 (and flip-x flip-y)
                 (set/rename-keys shape {:r1 :r3 :r2 :r4 :r3 :r1 :r4 :r2})

                 flip-x
                 (set/rename-keys shape {:r1 :r2 :r2 :r1 :r3 :r4 :r4 :r3})

                 flip-y
                 (set/rename-keys shape {:r1 :r4 :r2 :r3 :r3 :r2 :r4 :r1})

                 :else
                 shape)]
    (select-keys shape measure-attrs)))

(mf/defc numeric-input-wrapper*
  {::mf/private true}
  [{:keys [values name applied-tokens align on-detach] :rest props}]
  (let [tokens (mf/use-ctx muc/active-tokens-by-type)
        tokens (mf/with-memo [tokens name]
                 (delay
                   (-> (deref tokens)
                       (select-keys (get tk/tokens-by-input name))
                       (not-empty))))
        on-detach-attr
        (mf/use-fn
         (mf/deps on-detach name)
         #(on-detach % name))

        props  (mf/spread-props props
                                {:placeholder (if (or (= :multiple (:applied-tokens values))
                                                      (= :multiple (get values name)))
                                                (tr "settings.multiple") "--")
                                 :class (stl/css :numeric-input-measures)
                                 :applied-token (get applied-tokens name)
                                 :tokens tokens
                                 :align align
                                 :on-detach on-detach-attr
                                 :value (get values name)})]
    [:> numeric-input* props]))

(def ^:private xf:map-type (map :type))
(def ^:private xf:mapcat-type-to-options (mapcat type->options))

(mf/defc measures-menu*
  [{:keys [ids values applied-tokens type shapes]}]
  (let [token-numeric-inputs
        (features/use-feature "tokens/numeric-input")

        all-types
        (mf/with-memo [type shapes]
          ;; We only need this when multiple type is used
          (when (= type :multiple)
            (into #{} xf:map-type shapes)))

        options
        (mf/with-memo [type all-types]
          (if (= type :multiple)
            (into #{} xf:mapcat-type-to-options all-types)
            (type->options type)))

        frames
        (mf/with-memo [shapes]
          (let [objects (deref refs/workspace-page-objects)]
            (into [] (comp (keep :frame-id)
                           (map (d/getf objects)))
                  shapes)))

        selection-parents-ref
        (mf/with-memo [ids]
          (refs/parents-by-ids ids))

        selection-parents
        (mf/deref selection-parents-ref)

        shape
        (first shapes)

        flex-child?
        (some ctl/flex-layout? selection-parents)

        absolute?
        (ctl/item-absolute? shape)

        flex-container?
        (ctl/flex-layout? shape)

        flex-auto-width?
        (ctl/auto-width? shape)

        flex-fill-width?
        (ctl/fill-width? shape)

        flex-auto-height?
        (ctl/auto-height? shape)

        flex-fill-height?
        (ctl/fill-height? shape)

        disabled-position?
        (and flex-child? (not absolute?))

        disabled-width-sizing?
        (and (or flex-child? flex-container?)
             (or flex-auto-width? flex-fill-width?)
             (not absolute?))

        disabled-height-sizing?
        (and (or flex-child? flex-container?)
             (or flex-auto-height? flex-fill-height?)
             (not absolute?))

        ;; To show interactively the measures while the user is manipulating
        ;; the shape with the mouse, generate a copy of the shapes applying
        ;; the transient transformations.
        shapes
        (mf/with-memo [shapes frames]
          (map gsh/translate-to-frame shapes frames))

        ;; We repeatedly obtain the first shape after the
        ;; transformation.
        shape
        (first shapes)

        ;; For rotated or stretched shapes, the origin point we show in the menu
        ;; is not the (:x :y) shape attribute, but the top left coordinate of the
        ;; wrapping rectangle.
        values
        (let [rect  (-> (get shape :points)
                        (grc/points->rect))
              val-x (get values :x)
              val-y (get values :y)]
          (cond-> values
            (not= val-x :multiple) (assoc :x (dm/get-prop rect :x))
            (not= val-y :multiple) (assoc :y (dm/get-prop rect :y))
            ;; In case of multiple selection, the origin point has been already
            ;; calculated and given in the fake :ox and :oy attributes. See
            ;; common/src/app/common/attrs.cljc
            (and (= val-x :multiple)
                 (some? (:ox values)))
            (assoc :x (:ox values))

            (and (= val-y :multiple)
                 (some? (:oy values)))
            (assoc :y (:oy values))))

        ;; For :height and :width we take those in the :selrect attribute, because
        ;; not all shapes have an own :width and :height (e. g. paths). Here the
        ;; rotation is ignored (selrect always has the original size excluding
        ;; transforms).
        values
        (let [selrect  (get shape :selrect)
              rotation (get shape :rotation 0)]
          (cond-> values
            (not= (:width values) :multiple) (assoc :width (dm/get-prop selrect :width))
            (not= (:height values) :multiple) (assoc :height (dm/get-prop selrect :height))
            (not= (:rotation values) :multiple) (assoc :rotation rotation)))

        proportion-lock
        (get values :proportion-lock)

        clip-content-ref
        (mf/use-ref nil)

        show-in-viewer-ref
        (mf/use-ref nil)

        ;; PRESETS
        preset-state*
        (mf/use-state false)

        show-presets-dropdown?
        (deref preset-state*)

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

        orientation
        (when (= type :frame)
          (if (> (:width values) (:height values))
            :horiz
            :vert))

        on-orientation-change
        (mf/use-fn
         (mf/deps ids)
         (fn [orientation]
           (st/emit! (udw/change-orientation ids (keyword orientation)))))

        ;; SIZE AND PROPORTION LOCK
        do-size-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value attr]
           (binding [cts/*wasm-sync* true]
             (st/emit! (udw/trigger-bounding-box-cloaking ids)
                       (udw/update-dimensions ids attr value)))))

        on-size-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value attr]
           (if (or (string? value) (number? value))
             (do
               (st/emit! (udw/trigger-bounding-box-cloaking ids))
               (binding [cts/*wasm-sync* true]
                 (run! #(do-size-change value attr) shapes)))
             (do
               (let [resolved-value (:resolved-value (first value))]
                 (st/emit! (udw/trigger-bounding-box-cloaking ids)
                           (dwta/toggle-token {:token (first value)
                                               :attrs #{attr}
                                               :shapes shapes}))
                 (binding [cts/*wasm-sync* true]
                   (run! #(do-size-change resolved-value attr) shapes)))))))

        on-proportion-lock-change
        (mf/use-fn
         (mf/deps ids proportion-lock)
         (fn [_]
           (let [new-lock (if (= proportion-lock :multiple) true (not proportion-lock))]
             (run! #(st/emit! (udw/set-shape-proportion-lock % new-lock)) ids))))

        ;; POSITION
        do-position-change
        (mf/use-fn
         (fn [shape' value attr]
           (st/emit! (udw/update-position (:id shape') {attr value}))))

        on-position-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value attr]
           (if (or (string? value) (number? value))
             (do
               (st/emit! (udw/trigger-bounding-box-cloaking ids))
               (binding [cts/*wasm-sync* true]
                 (run! #(do-position-change %1 value attr) shapes)))
             (do
               (let [resolved-value (:resolved-value (first value))]
                 (st/emit! (udw/trigger-bounding-box-cloaking ids)
                           (dwta/toggle-token {:token (first value)
                                               :attrs #{attr}
                                               :shapes shapes}))
                 (binding [cts/*wasm-sync* true]
                   (run! #(do-position-change %1 resolved-value attr) shapes)))))))

        ;; ROTATION
        do-rotation-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (udw/increase-rotation ids value))))

        on-rotation-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (if (or (string? value) (number? value))
             (do
               (st/emit! (udw/trigger-bounding-box-cloaking ids))
               (binding [cts/*wasm-sync* true]
                 (run! #(do-rotation-change value) shapes)))
             (do
               (let [resolved-value (:resolved-value (first value))]
                 (st/emit! (udw/trigger-bounding-box-cloaking ids)
                           (dwta/toggle-token {:token (first value)
                                               :attrs #{:rotation}
                                               :shapes shapes}))
                 (binding [cts/*wasm-sync* true]
                   (run! #(do-rotation-change resolved-value) shapes)))))))

        on-width-change
        (mf/use-fn (mf/deps on-size-change) #(on-size-change % :width))

        on-height-change
        (mf/use-fn (mf/deps on-size-change) #(on-size-change % :height))

        on-pos-x-change
        (mf/use-fn (mf/deps on-position-change) #(on-position-change % :x))

        on-pos-y-change
        (mf/use-fn (mf/deps on-position-change) #(on-position-change % :y))


        ;; DETACH
        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token attr]
           (st/emit! (dwta/unapply-token {:token (first token)
                                          :attributes #{attr}
                                          :shape-ids ids}))))

        ;; CLIP CONTENT AND SHOW IN VIEWER
        on-change-clip-content
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (st/emit! (dwsh/update-shapes ids (fn [shape] (assoc shape :show-content (not value))))))))

        on-change-show-in-viewer
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)
                 undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id)
                       (dwsh/update-shapes ids (fn [shape] (cls/change-show-in-viewer shape (not value)))))

             (when-not value
               ;; when a frame is no longer shown in view mode, cannot
               ;; have interactions that navigate to it.
               (run! st/emit! (map #(dwi/remove-all-interactions-nav-to %) ids)))

             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        handle-fit-content
        (mf/use-fn
         (fn []
           (st/emit! (dwt/selected-fit-content))))]

    [:div {:class (stl/css :element-set)}
     (when (and (options :presets)
                (or (nil? all-types) (= (count all-types) 1)))
       [:div {:class (stl/css :presets)}
        [:div {:class (stl/css-case  :presets-wrapper true
                                     :opened show-presets-dropdown?)
               :on-click open-presets}
         [:span {:class (stl/css :select-name)} (tr "workspace.options.size-presets")]
         [:span {:class (stl/css :collapsed-icon)} deprecated-icon/arrow]

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
                    [:span {:class (stl/css :check-icon)} deprecated-icon/tick])])))]]]

        [:& radio-buttons {:selected (or (d/name orientation) "")
                           :on-change on-orientation-change
                           :name "frame-orientation"
                           :wide true
                           :class (stl/css :radio-buttons)}
         [:& radio-button {:icon deprecated-icon/size-vertical
                           :value "vert"
                           :id "size-vertical"}]
         [:& radio-button {:icon deprecated-icon/size-horizontal
                           :value "horiz"
                           :id "size-horizontal"}]]
        [:> icon-button*
         {:variant "ghost"
          :aria-label (tr "workspace.options.fit-content")
          :on-pointer-down handle-fit-content
          :icon i/fit-content}]])

     (when (options :size)
       [:div {:class (stl/css :size)}
        (if token-numeric-inputs
          [:*
           [:> numeric-input-wrapper*
            {:disabled disabled-width-sizing?
             :on-change on-width-change
             :on-detach on-detach-token
             :icon i/character-w
             :min 0.01
             :name :width
             :property (tr "workspace.options.width")
             :applied-tokens applied-tokens
             :values values}]

           [:> numeric-input-wrapper*
            {:disabled disabled-height-sizing?
             :on-change on-height-change
             :on-detach on-detach-token
             :min 0.01
             :icon i/character-h
             :name :height
             :align :right
             :property (tr "workspace.options.height")
             :applied-tokens applied-tokens
             :values values}]]

          [:*
           [:div {:class (stl/css-case :width true
                                       :disabled disabled-width-sizing?)
                  :title (tr "workspace.options.width")}
            [:span {:class (stl/css :icon-text)} "W"]
            [:> deprecated-input/numeric-input*
             {:min 0.01
              :no-validate true
              :placeholder (if (= :multiple (:width values)) (tr "settings.multiple") "--")
              :on-change on-width-change
              :disabled disabled-width-sizing?
              :class (stl/css :numeric-input)
              :value (:width values)}]]
           [:div {:class (stl/css-case :height true
                                       :disabled disabled-height-sizing?)
                  :title (tr "workspace.options.height")}
            [:span {:class (stl/css :icon-text)} "H"]
            [:> deprecated-input/numeric-input* {:min 0.01
                                                 :no-validate true
                                                 :placeholder (if (= :multiple (:height values)) (tr "settings.multiple") "--")
                                                 :on-change on-height-change
                                                 :disabled disabled-height-sizing?
                                                 :class (stl/css :numeric-input)
                                                 :value (:height values)}]]])

        [:> icon-button* {:variant "ghost"
                          :tooltip-placement "top-left"
                          :icon (if proportion-lock "lock" "unlock")
                          :class (stl/css-case :selected (true? proportion-lock))
                          :disabled (= proportion-lock :multiple)
                          :aria-label (if proportion-lock (tr "workspace.options.size.unlock") (tr "workspace.options.size.lock"))
                          :on-click on-proportion-lock-change}]])

     (when (options :position)
       [:div {:class (stl/css :position)}
        (if token-numeric-inputs
          [:*
           [:> numeric-input-wrapper*
            {:disabled disabled-position?
             :on-change on-pos-x-change
             :on-detach on-detach-token
             :icon i/character-x
             :name :x
             :property (tr "workspace.options.x")
             :applied-tokens applied-tokens
             :values values}]
           [:> numeric-input-wrapper*
            {:disabled disabled-position?
             :on-change on-pos-y-change
             :on-detach on-detach-token
             :icon i/character-y
             :name :y
             :align :right
             :property (tr "workspace.options.y")
             :applied-tokens applied-tokens
             :values values}]]

          [:*
           [:div {:class (stl/css-case :x-position true
                                       :disabled disabled-position?)
                  :title (tr "workspace.options.x")}
            [:span {:class (stl/css :icon-text)} "X"]
            [:> deprecated-input/numeric-input* {:no-validate true
                                                 :placeholder (if (= :multiple (:x values)) (tr "settings.multiple") "--")
                                                 :on-change on-pos-x-change
                                                 :disabled disabled-position?
                                                 :class (stl/css :numeric-input)
                                                 :value (:x values)}]]

           [:div {:class (stl/css-case :y-position true
                                       :disabled disabled-position?)
                  :title (tr "workspace.options.y")}
            [:span {:class (stl/css :icon-text)} "Y"]
            [:> deprecated-input/numeric-input* {:no-validate true
                                                 :placeholder (if (= :multiple (:y values)) (tr "settings.multiple") "--")
                                                 :disabled disabled-position?
                                                 :on-change on-pos-y-change
                                                 :class (stl/css :numeric-input)
                                                 :value (:y values)}]]])])

     (when (or (options :rotation) (options :radius))
       [:div {:class (stl/css :rotation-radius)}
        (when (options :rotation)
          (if token-numeric-inputs
            [:> numeric-input-wrapper*
             {:on-change on-rotation-change
              :on-detach on-detach-token
              :icon i/rotation
              :min -359
              :max 359
              :name :rotation
              :property (tr "workspace.options.rotation")
              :applied-tokens applied-tokens
              :values values}]

            [:div {:class (stl/css :rotation)
                   :title (tr "workspace.options.rotation")}
             [:span {:class (stl/css :icon)}  deprecated-icon/rotation]
             [:> deprecated-input/numeric-input*
              {:no-validate true
               :min -359
               :max 359
               :data-wrap true
               :placeholder (if (= :multiple (:rotation values)) (tr "settings.multiple") "--")
               :on-change on-rotation-change
               :class (stl/css :numeric-input)
               :value (:rotation values)}]]))
        (when (options :radius)
          [:> border-radius-menu* {:class (stl/css :border-radius)
                                   :ids ids
                                   :values values
                                   :applied-tokens applied-tokens
                                   :shapes shapes
                                   :shape shape}])])
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

            [:> icon* {:icon-id i/clip-content}]]])
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
            [:> icon* {:icon-id i/play}]]])])]))
