;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
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
   [rumext.alpha :as mf]))

;; --- Refs

(def picking-color?
  (l/derived :picking-color? refs/workspace-local))

(def picked-color
  (l/derived :picked-color refs/workspace-local))

(def picked-color-select
  (l/derived :picked-color-select refs/workspace-local))

(def viewport
  (l/derived (l/in [:workspace-local :vport]) st/state))

(def editing-spot-state-ref
  (l/derived (l/in [:workspace-local :editing-stop]) st/state))

(def current-gradient-ref
  (l/derived (l/in [:workspace-local :current-gradient]) st/state))

;; --- Color Picker Modal

(defn color->components [value opacity]
  (let [value (if (uc/hex? value) value "#000000")
        [r g b] (uc/hex->rgb value)
        [h s v] (uc/hex->hsv value)]

    {:hex (or value "000000")
     :alpha (or opacity 1)
     :r r :g g :b b
     :h h :s s :v v}))

(defn data->state [{:keys [color opacity gradient]}]
  (let [type (cond
               (nil? gradient) :color
               (= :linear (:type gradient)) :linear-gradient
               (= :radial (:type gradient)) :radial-gradient)

        parse-stop (fn [{:keys [offset color opacity]}]
                     (vector offset (color->components color opacity)))

        stops (when gradient
                (map parse-stop (:stops gradient)))

        current-color (if (nil? gradient)
                        (color->components color opacity)
                        (-> stops first second))

        gradient-data (select-keys gradient [:start-x :start-y
                                             :end-x :end-y
                                             :width])]

    (cond-> {:type type
             :current-color current-color}
      gradient (assoc :gradient-data gradient-data)
      stops    (assoc :stops (into {} stops))
      stops    (assoc :editing-stop (-> stops first first)))))

(defn state->data [{:keys [type current-color stops gradient-data]}]
  (if (= type :color)
    {:color (:hex current-color)
     :opacity (:alpha current-color)}

    (let [gradient-type (case type
                          :linear-gradient :linear
                          :radial-gradient :radial)
          parse-stop (fn [[offset {:keys [hex alpha]}]]
                       (hash-map :offset offset
                                 :color hex
                                 :opacity alpha))]
      {:gradient (-> {:type gradient-type
                      :stops (mapv parse-stop stops)}
                     (merge gradient-data))})))

(defn create-gradient-data [type]
  {:start-x 0.5
   :start-y (if (= type :linear-gradient) 0.0 0.5)
   :end-x   0.5
   :end-y   1
   :width  1.0})

(mf/defc colorpicker
  [{:keys [data disable-gradient disable-opacity on-change on-accept]}]
  (let [state (mf/use-state (data->state data))
        active-tab (mf/use-state :ramp #_:harmony #_:hsva)

        ref-picker (mf/use-ref)

        dirty? (mf/use-var false)
        last-color (mf/use-var data)

        picking-color? (mf/deref picking-color?)
        picked-color (mf/deref picked-color)
        picked-color-select (mf/deref picked-color-select)

        editing-spot-state (mf/deref editing-spot-state-ref)
        current-gradient (mf/deref current-gradient-ref)

        current-color (:current-color @state)

        change-tab
        (fn [tab]
          #(reset! active-tab tab))

        handle-change-color
        (fn [changes]
          (let [editing-stop (:editing-stop @state)]
            (swap! state #(cond-> %
                            true (update :current-color merge changes)
                            editing-stop (update-in [:stops editing-stop] merge changes)))
            (reset! dirty? true)))

        handle-click-picker (fn []
                              (if picking-color?
                                (do (modal/disallow-click-outside!)
                                    (st/emit! (dc/stop-picker)))
                                (do (modal/allow-click-outside!)
                                    (st/emit! (dc/start-picker)))))

        handle-change-stop
        (fn [offset]
          (when-let [offset-color (get-in @state [:stops offset])]
            (swap! state assoc
                   :current-color offset-color
                   :editing-stop offset)

            (st/emit! (dc/select-gradient-stop offset))))

        on-select-library-color
        (fn [color]
          (let [editing-stop (:editing-stop @state)
                is-gradient? (some? (:gradient color))]
            (if (and (some? editing-stop) (not is-gradient?))
              (handle-change-color (color->components (:color color) (:opacity color)))
              (do (reset! state (data->state color))
                  (on-change color)))))

        on-add-library-color
        (fn [_]
          (st/emit! (dwl/add-color (state->data @state))))

        on-activate-gradient
        (fn [type]
          (fn []
            (reset! dirty? true)
            (if (= type (:type @state))
              (do
                (swap! state assoc :type :color)
                (swap! state dissoc :editing-stop :stops :gradient-data)
                (st/emit! (dc/stop-gradient)))
              (let [gradient-data (create-gradient-data type)]
                (swap! state assoc :type type :gradient-data gradient-data)
                (when (not (:stops @state))
                  (swap! state assoc
                         :editing-stop 0
                         :stops {0 (:current-color @state)
                                 1 (-> (:current-color @state)
                                       (assoc :alpha 0))}))))))]

    ;; Updates the CSS color variable when there is a change in the color
    (mf/use-effect
     (mf/deps current-color)
     (fn [] (let [node (mf/ref-val ref-picker)
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
              (dom/set-css-property! node "--saturation-grad-to" (format-hsl hsl-to)))))

    ;; When closing the modal we update the recent-color list
    (mf/use-effect
     #(fn []
        (st/emit! (dc/stop-picker))
        (when @last-color
          (st/emit! (dwl/add-recent-color @last-color)))))

    ;; Updates color when used el pixel picker
    (mf/use-effect
     (mf/deps picking-color? picked-color-select)
     (fn []
       (when (and picking-color? picked-color-select)
         (let [[r g b alpha] picked-color
               hex (uc/rgb->hex [r g b])
               [h s v] (uc/hex->hsv hex)]
           (handle-change-color {:hex hex
                                 :r r :g g :b b
                                 :h h :s s :v v
                                 :alpha (/ alpha 255)})))))

    ;; Changes when another gradient handler is selected
    (mf/use-effect
     (mf/deps editing-spot-state)
     #(when (not= editing-spot-state (:editing-stop @state))
        (handle-change-stop (or editing-spot-state 0))))

    ;; Changes on the viewport when moving a gradient handler
    (mf/use-effect
     (mf/deps current-gradient)
     (fn []
       (when current-gradient
         (let [gradient-data (select-keys current-gradient [:start-x :start-y
                                                            :end-x :end-y
                                                            :width])]
           (when (not= (:gradient-data @state) gradient-data)
             (reset! dirty? true)
             (swap! state assoc :gradient-data gradient-data))))))

    ;; Check if we've opened a color with gradient
    (mf/use-effect
     (fn []
       (when (:gradient data)
         (st/emit! (dc/start-gradient (:gradient data))))

       ;; on-unmount we stop the handlers
       #(st/emit! (dc/stop-gradient))))

    ;; Send the properties to the store
    (mf/use-effect
     (mf/deps @state)
     (fn []
       (when @dirty?
         (let [color (state->data @state)]
           (reset! dirty? false)
           (reset! last-color color)
           (when (:gradient color)
             (st/emit! (dc/start-gradient (:gradient color))))
           (on-change color)))))

    [:div.colorpicker {:ref ref-picker}
     [:div.colorpicker-content
      [:div.top-actions
       [:button.picker-btn
        {:class (when picking-color? "active")
         :on-click handle-click-picker}
        i/picker]

       (when (not disable-gradient)
         [:div.gradients-buttons
          [:button.gradient.linear-gradient
           {:on-click (on-activate-gradient :linear-gradient)
            :class (when (= :linear-gradient (:type @state)) "active")}]

          [:button.gradient.radial-gradient
           {:on-click (on-activate-gradient :radial-gradient)
            :class (when (= :radial-gradient (:type @state)) "active")}]])]

      [:& gradients {:type (:type @state)
                     :stops (:stops @state)
                     :editing-stop (:editing-stop @state)
                     :on-select-stop handle-change-stop}]

      [:div.colorpicker-tabs
       [:div.colorpicker-tab.tooltip.tooltip-bottom.tooltip-expand
        {:class (when (= @active-tab :ramp) "active")
         :alt (tr "workspace.libraries.colors.rgba")
         :on-click (change-tab :ramp)} i/picker-ramp]
       [:div.colorpicker-tab.tooltip.tooltip-bottom.tooltip-expand
        {:class (when (= @active-tab :harmony) "active")
         :alt (tr "workspace.libraries.colors.rgb-complementary")
         :on-click (change-tab :harmony)} i/picker-harmony]
       [:div.colorpicker-tab.tooltip.tooltip-bottom.tooltip-expand
        {:class (when (= @active-tab :hsva) "active")
         :alt (tr "workspace.libraries.colors.hsv")
         :on-click (change-tab :hsva)} i/picker-hsv]]

      (if picking-color?
        [:div.picker-detail-wrapper
         [:div.center-circle]
         [:canvas#picker-detail {:width 200 :height 160}]]
        (case @active-tab
          :ramp [:& ramp-selector {:color current-color
                                   :disable-opacity disable-opacity
                                   :on-change handle-change-color}]
          :harmony [:& harmony-selector {:color current-color
                                         :disable-opacity disable-opacity
                                         :on-change handle-change-color}]
          :hsva [:& hsva-selector {:color current-color
                                   :disable-opacity disable-opacity
                                   :on-change handle-change-color}]
          nil))

      [:& color-inputs {:type (if (= @active-tab :hsva) :hsv :rgb)
                        :disable-opacity disable-opacity
                        :color current-color
                        :on-change handle-change-color}]

      [:& libraries {:current-color current-color
                     :disable-gradient disable-gradient
                     :disable-opacity disable-opacity
                     :on-select-color on-select-library-color
                     :on-add-library-color on-add-library-color}]

      (when on-accept
        [:div.actions
         [:button.btn-primary.btn-large
          {:on-click (fn []
                       (on-accept (state->data @state))
                       (modal/hide!))}
          (tr "workspace.libraries.colors.save-color")]])]]))

(defn calculate-position
  "Calculates the style properties for the given coordinates and position"
  [{vh :height} position x y]
  (let [;; picker height in pixels
        h 360
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

        handle-change (fn [new-data _shift-clicked?]
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

