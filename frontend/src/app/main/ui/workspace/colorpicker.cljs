;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [cuerdas.core :as str]
   [app.common.geom.point :as gpt]
   [app.common.math :as math]
   [app.common.uuid :refer [uuid]]
   [app.util.dom :as dom]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.colors :as dc]
   [app.main.data.modal :as modal]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t]]
   [app.main.ui.workspace.colorpicker.gradients :refer [gradients]]
   [app.main.ui.workspace.colorpicker.harmony :refer [harmony-selector]]
   [app.main.ui.workspace.colorpicker.hsva :refer [hsva-selector]]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector]]
   [app.main.ui.workspace.colorpicker.color-inputs :refer [color-inputs]]
   [app.main.ui.workspace.colorpicker.libraries :refer [libraries]]))

;; --- Refs

(def picking-color?
  (l/derived :picking-color? refs/workspace-local))

(def picked-color
  (l/derived :picked-color refs/workspace-local))

(def picked-color-select
  (l/derived :picked-color-select refs/workspace-local))

(def picked-shift?
  (l/derived :picked-shift? refs/workspace-local))

(def viewport
  (l/derived (l/in [:workspace-local :vport]) st/state))

(def editing-spot-state-ref
  (l/derived (l/in [:workspace-local :editing-stop]) st/state))

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
        locale (mf/deref i18n/locale)

        ref-picker (mf/use-ref)

        picking-color? (mf/deref picking-color?)
        picked-color (mf/deref picked-color)
        picked-color-select (mf/deref picked-color-select)
        picked-shift? (mf/deref picked-shift?)

        editing-spot-state (mf/deref editing-spot-state-ref)

        ;; data-ref (mf/use-var data)

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

            #_(when (:hex changes)
              (reset! value-ref (:hex changes)))

            ;; TODO: CHANGE TO SUPPORT GRADIENTS
            #_(on-change (:hex changes (:hex current-color))
                       (:alpha changes (:alpha current-color)))))

        handle-change-stop
        (fn [offset]
          (when-let [offset-color (get-in @state [:stops offset])]
            (do (swap! state assoc
                       :current-color offset-color
                       :editing-stop offset)

                (st/emit! (dc/select-gradient-stop offset)))))

        on-select-library-color
        (fn [color] (prn "color" color))

        on-activate-gradient
        (fn [type]
          (fn []
            (if (= type (:type @state))
              (do
                (swap! state assoc :type :color)
                (swap! state dissoc :editing-stop :stops :gradient-data))
              (do
                (swap! state assoc :type type
                       :gradient-data (create-gradient-data type))
                (when (not (:stops @state))
                  (swap! state assoc
                         :editing-stop 0
                         :stops {0 (:current-color @state)
                                 1 (-> (:current-color @state)
                                       (assoc :alpha 0))}))))))]

    ;; Update state when there is a change in the props upstream
    ;; TODO: Review for gradients
    #_(mf/use-effect
     (mf/deps value opacity)
     (fn []
       (swap! state assoc current-color (as-color-components value opacity))))

    ;; Updates the CSS color variable when there is a change in the color
    (mf/use-effect
     (mf/deps current-color)
     (fn [] (let [node (mf/ref-val ref-picker)
                  {:keys [r g b h s v]} current-color
                  rgb [r g b]
                  hue-rgb (uc/hsv->rgb [h 1.0 255])
                  hsl-from (uc/hsv->hsl [h 0.0 v])
                  hsl-to (uc/hsv->hsl [h 1.0 v])

                  format-hsl (fn [[h s l]]
                               (str/fmt "hsl(%s, %s, %s)"
                                        h
                                        (str (* s 100) "%")
                                        (str (* l 100) "%")))]
              (dom/set-css-property node "--color" (str/join ", " rgb))
              (dom/set-css-property node "--hue-rgb" (str/join ", " hue-rgb))
              (dom/set-css-property node "--saturation-grad-from" (format-hsl hsl-from))
              (dom/set-css-property node "--saturation-grad-to" (format-hsl hsl-to)))))

    ;; When closing the modal we update the recent-color list
    #_(mf/use-effect
     (fn [] (fn []
              (st/emit! (dc/stop-picker))
              (st/emit! (dwl/add-recent-color (state->data @state))))))

    (mf/use-effect
     (mf/deps picking-color? picked-color)
     (fn []
       (when picking-color?
         (let [[r g b] (or picked-color [0 0 0])
               hex (uc/rgb->hex [r g b])
               [h s v] (uc/hex->hsv hex)]

           (swap! state update :current-color assoc
                  :r r :g g :b b
                  :h h :s s :v v
                  :hex hex)

           ;; TODO: UPDATE TO USE GRADIENTS
           #_(reset! value-ref hex)
           #_(when picked-color-select
               (on-change hex (:alpha current-color) nil nil picked-shift?))))))

    ;; TODO: UPDATE TO USE GRADIENTS
    #_(mf/use-effect
     (mf/deps picking-color? picked-color-select)
     (fn [] (when (and picking-color? picked-color-select)
              (on-change (:hex current-color) (:alpha current-color) nil nil picked-shift?))))

    (mf/use-effect
     (mf/deps editing-spot-state)
     #(when (not= editing-spot-state (:editing-stop @state))
        (handle-change-stop (or editing-spot-state 0))))

    (mf/use-effect
     (mf/deps data)
     #(if-let [gradient-data (-> data data->state :gradient-data)]
        (swap! state assoc :gradient-data gradient-data)))

    (mf/use-effect
     (mf/deps @state)
     (fn []
       (on-change (state->data @state))))

    [:div.colorpicker {:ref ref-picker}
     [:div.colorpicker-content
      [:div.top-actions
       [:button.picker-btn
        {:class (when picking-color? "active")
         :on-click (fn []
                     (modal/allow-click-outside!)
                     (st/emit! (dc/start-picker)))}
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
                     :on-select-color on-select-library-color}]]
     [:div.colorpicker-tabs
      [:div.colorpicker-tab {:class (when (= @active-tab :ramp) "active")
                             :on-click (change-tab :ramp)} i/picker-ramp]
      [:div.colorpicker-tab {:class (when (= @active-tab :harmony) "active")
                             :on-click (change-tab :harmony)} i/picker-harmony]
      [:div.colorpicker-tab {:class (when (= @active-tab :hsva) "active")
                             :on-click (change-tab :hsva)} i/picker-hsv]]
     (when on-accept
       [:div.actions
        [:button.btn-primary.btn-large
         {:on-click (fn []
                      ;; TODO: REVIEW FOR GRADIENTS
                      #_(on-accept @value-ref)
                      (modal/hide!))}
         (t locale "workspace.libraries.colors.save-color")]])])
  )

(defn calculate-position
  "Calculates the style properties for the given coordinates and position"
  [{vh :height} position x y]
  (let [;; picker height in pixels
        h 360
        ;; Checks for overflow outside the viewport height
        overflow-fix (max 0 (+ y (- 50) h (- vh)))]
    (cond
      (or (nil? x) (nil? y)) {:left "auto" :right "16rem" :top "4rem"}
      (= position :left) {:left (str (- x 270) "px")
                          :top (str (- y 50 overflow-fix) "px")}
      :else {:left (str (+ x 24) "px")
             :top (str (- y 50 overflow-fix) "px")})))


(mf/defc colorpicker-modal
  {::mf/register modal/components
   ::mf/register-as :colorpicker}
  [{:keys [x y default data page position
           disable-gradient
           disable-opacity
           on-change on-close on-accept] :as props}]
  (let [vport (mf/deref viewport)
        dirty? (mf/use-var false)
        last-change (mf/use-var nil)
        position (or position :left)
        style (calculate-position vport position x y)

        handle-change (fn [new-data shift-clicked?]
                        (reset! dirty? (not= data new-data))
                        (reset! last-change new-data)
                        (when on-change
                          (on-change new-data)))

        ;; handle-change (fn [new-value new-opacity id file-id shift-clicked?]
        ;;                 (when (or (not= new-value value) (not= new-opacity opacity))
        ;;                   (reset! dirty? true))
        ;;                 (reset! last-change [new-value new-opacity id file-id])
        ;;                 (when on-change
        ;;                   (on-change new-value new-opacity id file-id shift-clicked?)))
        ]

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

