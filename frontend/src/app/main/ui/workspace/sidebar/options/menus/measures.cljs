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
   [app.common.logic.shapes :as cls]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.main.constants :refer [size-presets]]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.border-radius :refer  [border-radius-menu]]
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
    :image   rect-options
    :path    generic-options
    :rect    rect-options
    :svg-raw generic-options
    :text    generic-options))

(def ^:private clip-content-icon (i/icon-xref :clip-content (stl/css :checkbox-button)))
(def ^:private play-icon (i/icon-xref :play (stl/css :checkbox-button)))
(def ^:private locked-icon (i/icon-xref :detach (stl/css :lock-ratio-icon)))
(def ^:private unlocked-icon (i/icon-xref :detached (stl/css :lock-ratio-icon)))

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

(mf/defc measures-menu*
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [ids ids-with-children values type all-types shape]}]
  (let [options
        (mf/with-memo [type all-types]
          (if (= type :multiple)
            (into #{} (mapcat type->options) all-types)
            (type->options type)))

        ids-with-children
        (or ids-with-children ids)

        old-shapes
        (if (= type :multiple)
          (deref (refs/objects-by-id ids))
          [shape])

        frames
        (map #(deref (refs/object-by-id (:frame-id %))) old-shapes)

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
           (binding [cts/*wasm-sync* true]
             (st/emit! (udw/trigger-bounding-box-cloaking ids)
                       (udw/update-dimensions ids attr value)))))

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
         (fn [shape' value attr]
           (st/emit! (udw/update-position (:id shape') {attr value}))))

        on-position-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value attr]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (binding [cts/*wasm-sync* true]
             (doall (map #(do-position-change %1 value attr) shapes)))))

        ;; ROTATION

        on-rotation-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (binding [cts/*wasm-sync* true]
             (st/emit! (udw/trigger-bounding-box-cloaking ids)
                       (udw/increase-rotation ids value)))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)

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
                 ;; when a frame is no longer shown in view mode, cannot have
                 ;; interactions that navigate to it.
               (apply st/emit! (map #(dwi/remove-all-interactions-nav-to %) ids)))

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
                           :id "size-horizontal"}]]
        [:> icon-button*
         {:variant "ghost"
          :aria-label (tr "workspace.options.fit-content")
          :title (tr "workspace.options.fit-content")
          :on-pointer-down handle-fit-content
          :icon "fit-content"}]])
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
                             :class (stl/css :numeric-input)
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
                             :class (stl/css :numeric-input)
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
                             :class (stl/css :numeric-input)
                             :value (:x values)}]]

        [:div {:class (stl/css-case :y-position true
                                    :disabled disabled-position-y?)
               :title (tr "workspace.options.y")}
         [:span {:class (stl/css :icon-text)} "Y"]
         [:> numeric-input* {:no-validate true
                             :placeholder (if (= :multiple (:y values)) (tr "settings.multiple") "--")
                             :disabled disabled-position-y?
                             :on-change on-pos-y-change
                             :class (stl/css :numeric-input)
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
             :class (stl/css :numeric-input)
             :value (:rotation values)}]])
        (when (options :radius)
          [:& border-radius-menu {:ids ids :ids-with-children ids-with-children :values values :shape shape}])])
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
