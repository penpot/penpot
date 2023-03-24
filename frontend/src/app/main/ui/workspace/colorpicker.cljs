;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.colorpicker.color-inputs :refer [color-inputs]]
   [app.main.ui.workspace.colorpicker.gradients :refer [gradients]]
   [app.main.ui.workspace.colorpicker.harmony :refer [harmony-selector]]
   [app.main.ui.workspace.colorpicker.hsva :refer [hsva-selector]]
   [app.main.ui.workspace.colorpicker.libraries :refer [libraries]]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector]]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Refs

(def picking-color?
  (l/derived :picking-color? refs/workspace-global))

(def picked-color
  (l/derived :picked-color refs/workspace-global))

(def picked-color-select
  (l/derived :picked-color-select refs/workspace-global))

(def viewport
  (l/derived :vport refs/workspace-local))

;; --- Color Picker Modal

(mf/defc colorpicker
  [{:keys [data disable-gradient disable-opacity on-change on-accept]}]
  (let [state               (mf/deref refs/colorpicker)
        node-ref            (mf/use-ref)

        ;; TODO: I think we need to put all this picking state under
        ;; the same object for avoid creating adhoc refs for each
        ;; value
        picking-color?      (mf/deref picking-color?)
        picked-color        (mf/deref picked-color)
        picked-color-select (mf/deref picked-color-select)

        current-color       (:current-color state)

        active-tab          (mf/use-state :ramp #_:harmony #_:hsva)
        set-ramp-tab!       (mf/use-fn #(reset! active-tab :ramp))
        set-harmony-tab!    (mf/use-fn #(reset! active-tab :harmony))
        set-hsva-tab!       (mf/use-fn #(reset! active-tab :hsva))

        drag?               (mf/use-state false)

        handle-change-color
        (mf/use-fn
         (mf/deps current-color @drag?)
         (fn [color]
           (when (or (not= (str/lower (:hex color)) (str/lower (:hex current-color)))
                     (not= (:h color) (:h current-color)))
             (let [recent-color (merge current-color color)
                   recent-color (dc/materialize-color-components recent-color)]
               (st/emit! (dc/update-colorpicker-color recent-color (not @drag?)))))))

        handle-click-picker
        (mf/use-fn
         (mf/deps picking-color?)
         (fn []
           (if picking-color?
             (do (modal/disallow-click-outside!)
                 (st/emit! (dc/stop-picker)))
             (do (modal/allow-click-outside!)
                 (st/emit! (dc/start-picker))))))

        handle-change-stop
        (mf/use-fn
         (fn [offset]
           (st/emit! (dc/select-colorpicker-gradient-stop offset))))

        on-select-library-color
        (mf/use-fn
         (fn [state color]
           (let [type-origin (:type state)
                 editig-stop-origin (:editing-stop state)
                 is-gradient? (some? (:gradient color))
                 change-to (fn [new-color]
                             (st/emit! (dc/update-colorpicker new-color))
                             (on-change new-color))
                 clean-stop (fn [stops index color]
                              (-> (nth stops index)
                                  (merge color)
                                  (assoc :offset index)
                                  (dissoc :r)
                                  (dissoc :g)
                                  (dissoc :b)
                                  (dissoc :alpha)
                                  (dissoc :s)
                                  (dissoc :h)
                                  (dissoc :v)
                                  (dissoc :hex)))
                 set-new-gradient (fn [state color index]
                                    (let [old-stops (:stops state)
                                          old-gradient (:gradient state)
                                          new-gradient (-> old-gradient
                                                           (cond-> (= index 0) (assoc  :stops [(clean-stop old-stops 0 color) (nth old-stops 1)]))
                                                           (cond-> (= index 1) (assoc  :stops [(nth old-stops 0) (clean-stop old-stops 1 color)]))
                                                           (dissoc :shape-id))]
                                      (change-to {:gradient new-gradient})))]
             ;; If we have any kind of gradient and:
             ;; Click on a solid color -> This color is applied to the selected offset
             ;; Click on a color with transparency -> The same to solid color will happend
             ;; Click on any kind of gradient -> The color changes completly to new gradient
             
             ;; If we have a non gradient color the new color is applied without any change
             (if (or (= :radial-gradient type-origin) (= :linear-gradient type-origin))
               (if is-gradient?
                 (change-to color)
                 (set-new-gradient state color editig-stop-origin))
               (change-to color)))))

        on-add-library-color
        (mf/use-fn
         (mf/deps state)
         (fn [_]
           (st/emit! (dwl/add-color (dc/get-color-from-colorpicker-state state)))))

        on-activate-linear-gradient
        (mf/use-fn #(st/emit! (dc/activate-colorpicker-gradient :linear-gradient)))

        on-activate-radial-gradient
        (mf/use-fn #(st/emit! (dc/activate-colorpicker-gradient :radial-gradient)))

        on-start-drag
        (mf/use-fn
         (fn []
           (reset! drag? true)
           (st/emit! (dwu/start-undo-transaction (mf/ref-val node-ref)))))

        on-finish-drag
        (mf/use-fn
         (fn []
           (reset! drag? false)
           (st/emit! (dwu/commit-undo-transaction (mf/ref-val node-ref)))))]

    ;; Initialize colorpicker state
    (mf/with-effect []
      (st/emit! (dc/initialize-colorpicker on-change))
      (partial st/emit! (dc/finalize-colorpicker)))

    ;; Update colorpicker with external color changes
    (mf/with-effect [data]
      (st/emit! (dc/update-colorpicker data)))

    ;; Updates the CSS color variable when there is a change in the color
    (mf/with-effect [current-color]
      (let [node (mf/ref-val node-ref)
            {:keys [r g b h v]} current-color
            rgb [r g b]
            hue-rgb (uc/hsv->rgb [h 1.0 255])
            hsl-from (uc/hsv->hsl [h 0.0 v])
            hsl-to (uc/hsv->hsl [h 1.0 v])

            format-hsl (fn [[h s l]]
                         (str/fmt "hsl(%s, %s, %s)"
                                  h
                                  (str (* s 100) "%")
                                  (str (* l 100) "%")))]
        (dom/set-css-property! node "--color" (str/join ", " rgb))
        (dom/set-css-property! node "--hue-rgb" (str/join ", " hue-rgb))
        (dom/set-css-property! node "--saturation-grad-from" (format-hsl hsl-from))
        (dom/set-css-property! node "--saturation-grad-to" (format-hsl hsl-to))))

    ;; Updates color when used el pixel picker
    (mf/with-effect [picking-color? picked-color picked-color-select]
      (when (and picking-color? picked-color picked-color-select)
        (let [[r g b alpha] picked-color
              hex (uc/rgb->hex [r g b])
              [h s v] (uc/hex->hsv hex)]
          (handle-change-color {:hex hex
                                :r r :g g :b b
                                :h h :s s :v v
                                :alpha (/ alpha 255)}))))

    [:div.colorpicker {:ref node-ref
                       :style {:touch-action "none"}}
     [:div.colorpicker-content
      [:div.top-actions
       [:button.picker-btn
        {:class (when picking-color? "active")
         :on-click handle-click-picker}
        i/picker]

       (when (not disable-gradient)
         [:div.gradients-buttons
          [:button.gradient.linear-gradient
           {:on-click on-activate-linear-gradient
            :class (when (= :linear-gradient (:type state)) "active")}]

          [:button.gradient.radial-gradient
           {:on-click on-activate-radial-gradient
            :class (when (= :radial-gradient (:type state)) "active")}]])]


      (when (or (= (:type state) :linear-gradient)
                (= (:type state) :radial-gradient))
        [:& gradients
         {:stops (:stops state)
          :editing-stop (:editing-stop state)
          :on-select-stop handle-change-stop}])

      [:div.colorpicker-tabs
       [:div.colorpicker-tab.tooltip.tooltip-bottom.tooltip-expand
        {:class (when (= @active-tab :ramp) "active")
         :alt (tr "workspace.libraries.colors.rgba")
         :on-click set-ramp-tab!} i/picker-ramp]
       [:div.colorpicker-tab.tooltip.tooltip-bottom.tooltip-expand
        {:class (when (= @active-tab :harmony) "active")
         :alt (tr "workspace.libraries.colors.rgb-complementary")
         :on-click set-harmony-tab!} i/picker-harmony]
       [:div.colorpicker-tab.tooltip.tooltip-bottom.tooltip-expand
        {:class (when (= @active-tab :hsva) "active")
         :alt (tr "workspace.libraries.colors.hsv")
         :on-click set-hsva-tab!} i/picker-hsv]]

      (if picking-color?
        [:div.picker-detail-wrapper
         [:div.center-circle]
         [:canvas#picker-detail {:width 200 :height 160}]]
        (case @active-tab
          :ramp
          [:& ramp-selector
           {:color current-color
            :disable-opacity disable-opacity
            :on-change handle-change-color
            :on-start-drag on-start-drag
            :on-finish-drag on-finish-drag}]
          :harmony
          [:& harmony-selector
           {:color current-color
            :disable-opacity disable-opacity
            :on-change handle-change-color
            :on-start-drag on-start-drag
            :on-finish-drag on-finish-drag}]
          :hsva
          [:& hsva-selector
           {:color current-color
            :disable-opacity disable-opacity
            :on-change handle-change-color
            :on-start-drag on-start-drag
            :on-finish-drag on-finish-drag}]
          nil))

      [:& color-inputs
       {:type (if (= @active-tab :hsva) :hsv :rgb)
        :disable-opacity disable-opacity
        :color current-color
        :on-change handle-change-color}]

      [:& libraries
       {:state state
        :current-color current-color
        :disable-gradient disable-gradient
        :disable-opacity disable-opacity
        :on-select-color on-select-library-color
        :on-add-library-color on-add-library-color}]

      (when on-accept
        [:div.actions
         [:button.btn-primary.btn-large
          {:on-click (fn []
                       (on-accept (dc/get-color-from-colorpicker-state state))
                       (modal/hide!))}
          (tr "workspace.libraries.colors.save-color")]])]]))

(defn calculate-position
  "Calculates the style properties for the given coordinates and position"
  [{vh :height} position x y]
  (let [;; picker height in pixels
        h 430
        ;; Checks for overflow outside the viewport height
        overflow-fix (max 0 (+ y (- 50) h (- vh)))]
    (cond
      (or (nil? x) (nil? y)) {:left "auto" :right "16rem" :top "4rem"}
      (= position :left) {:left (str (- x 250) "px")
                          :top (str (- y 50 overflow-fix) "px")}
      :else {:left (str (+ x 80) "px")
             :top (str (- y 70 overflow-fix) "px")})))


(mf/defc colorpicker-modal
  {::mf/register modal/components
   ::mf/register-as :colorpicker}
  [{:keys [x y data position
           disable-gradient
           disable-opacity
           on-change on-close on-accept] :as props}]
  (let [vport (mf/deref viewport)
        dirty? (mf/use-var false)
        last-change (mf/use-var nil)
        position (or position :left)
        style (calculate-position vport position x y)

        handle-change
        (fn [new-data]
          (reset! dirty? (not= data new-data))
          (reset! last-change new-data)
          (when on-change
            (on-change new-data)))]

    (mf/use-effect
     (fn []
       #(when (and @dirty? @last-change on-close)
          (on-close @last-change))))

    [:div.colorpicker-tooltip
     {:style (clj->js style)}
     [:& colorpicker {:data data
                      :disable-gradient disable-gradient
                      :disable-opacity disable-opacity
                      :on-change handle-change
                      :on-accept on-accept}]]))

