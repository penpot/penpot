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
   [app.main.store :as st]
   [app.main.ui.colorpicker :as cp]

   [cuerdas.core :as str]
   [app.util.dom :as dom]
   [app.util.color :as uc]
   [app.main.ui.icons :as i]
   [app.common.math :as math]
   [app.common.uuid :refer [uuid]]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.ui.modal :as modal]
   [okulary.core :as l]
   [app.main.refs :as refs]))

(def recent-colors
  (letfn [(selector [{:keys [objects]}]
            (as-> {} $
              (reduce (fn [acc shape]
                        (-> acc
                            (update (:fill-color shape) (fnil inc 0))
                            (update (:stroke-color shape) (fnil inc 0))))
                      $ (vals objects))
              (reverse (sort-by second $))
              (map first $)
              (remove nil? $)))]
    (l/derived selector st/state)))


;; --- Color Picker Modal

(mf/defc value-selector [{:keys [hue saturation value on-change]}]
  (let [dragging? (mf/use-state false)
        calculate-pos
        (fn [ev]
          (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x y]} (-> ev dom/get-client-position)
                px (math/clamp (/ (- x left) (- right left)) 0 1)
                py (* 255 (- 1 (math/clamp (/ (- y top) (- bottom top)) 0 1)))]
            (on-change px py)))]
    [:div.value-selector
     {:on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-pointer-down (partial dom/capture-pointer)
      :on-pointer-up (partial dom/release-pointer)
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* 100 saturation) "%")
                            :top (str (* 100 (- 1 (/ value 255))) "%")}}]]))

(mf/defc hue-selector [{:keys [hue on-change]}]
  (let [dragging? (mf/use-state false)
        calculate-pos
        (fn [ev]
          (let [{:keys [left right]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x]} (-> ev dom/get-client-position)
                px (math/clamp (/ (- x left) (- right left)) 0 1)]
            (on-change (* px 360))))]
    [:div.hue-selector
     {:on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-pointer-down (partial dom/capture-pointer)
      :on-pointer-up (partial dom/release-pointer)
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* (/ hue 360) 100) "%")}}]]))

(mf/defc opacity-selector [{:keys [opacity on-change]}]
  (let [dragging? (mf/use-state false)
        calculate-pos
        (fn [ev]
          (let [{:keys [left right]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x]} (-> ev dom/get-client-position)
                px (math/clamp (/ (- x left) (- right left)) 0 1)]
            (on-change px)))]
    [:div.opacity-selector
     {:on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-pointer-down (partial dom/capture-pointer)
      :on-pointer-up (partial dom/release-pointer)
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* opacity 100) "%")}}]]))

(defn as-color-components [value opacity]
  (let [[r g b] (uc/hex->rgb (or value "000000"))
        [h s v] (uc/hex->hsv (or value "000000"))]
    {:hex (or value "000000")
     :alpha (or opacity 1)
     :r r :g g :b b
     :h h :s s :v v}))

(mf/defc colorpicker
  [{:keys [value opacity on-change on-accept]}]
  (let [current-color (mf/use-state (as-color-components value opacity))
        selected-library (mf/use-state "recent")
        current-library-colors (mf/use-state [])
        ref-picker (mf/use-ref)

        file-colors (mf/deref refs/workspace-file-colors)
        shared-libs (mf/deref refs/workspace-libraries)
        recent-colors (mf/deref refs/workspace-recent-colors)

        value-ref (mf/use-var value)

        on-change (or on-change identity)]

    ;; Update state when there is a change in the props upstream
    (mf/use-effect
     (mf/deps value opacity)
     (fn []
       (reset! current-color (as-color-components value opacity))))

    ;; Updates the CSS color variable when there is a change in the color
    (mf/use-effect
     (mf/deps @current-color)
     (fn [] (let [node (mf/ref-val ref-picker)
                  rgb [(:r @current-color) (:g @current-color) (:b @current-color)]
                  hue-rgb (uc/hsv->rgb [(:h @current-color) 1.0 255])]
              (dom/set-css-property node "--color" (str/join ", " rgb))
              (dom/set-css-property node "--hue" (str/join ", " hue-rgb)))))

    ;; Load library colors when the select is changed
    (mf/use-effect
     (mf/deps @selected-library)
     (fn [] (cond
              (= @selected-library "recent") (reset! current-library-colors (reverse (or recent-colors [])))
              (= @selected-library "file") (reset! current-library-colors (into [] (map :value (vals file-colors))))
              :else ;; Library UUID
              (reset! current-library-colors (into [] (map :value (vals (get-in shared-libs [(uuid @selected-library) :data :colors]))))))))

    ;; If the file colors change and the file option is selected updates the state
    (mf/use-effect
     (mf/deps file-colors)
     (fn [] (when (= @selected-library "file")
              (reset! current-library-colors (into [] (map :value (vals file-colors)))))))

    ;; When closing the modal we update the recent-color list
    (mf/use-effect
     (fn [] #(st/emit! (dwl/add-recent-color @value-ref))))

    [:div.colorpicker-v2 {:ref ref-picker}
     [:& value-selector {:hue (:h @current-color)
                         :saturation (:s @current-color)
                         :value (:v @current-color)
                         :on-change (fn [s v]
                                      (let [hex (uc/hsv->hex [(:h @current-color) s v])
                                            [r g b] (uc/hex->rgb hex)]
                                        (swap! current-color assoc
                                               :hex hex
                                               :r r :g g :b b
                                               :s s :v v)
                                        (reset! value-ref hex)
                                        (on-change hex (:alpha @current-color))))}]
     [:div.shade-selector
      [:div.color-bullet]
      [:& hue-selector {:hue (:h @current-color)
                        :on-change (fn [h]
                                     (let [hex (uc/hsv->hex [h (:s @current-color) (:v @current-color)])
                                           [r g b] (uc/hex->rgb hex)]
                                       (swap! current-color assoc
                                              :hex hex
                                              :r r :g g :b b
                                              :h h )
                                       (reset! value-ref hex)
                                       (on-change hex (:alpha @current-color))))}]
      [:& opacity-selector {:opacity (:alpha @current-color)
                            :on-change (fn [alpha]
                                         (swap! current-color assoc :alpha alpha)
                                         (on-change (:hex @current-color) alpha))}]]

     [:div.color-values
      [:input.hex-value {:id "hex-value"
                         :value (:hex @current-color)
                         :on-change (fn [e]
                                      (let [val (-> e dom/get-target dom/get-value)
                                            val (if (= (first val) \#) val (str \# val))]
                                        (swap! current-color assoc :hex val)
                                        (when (uc/hex? val)
                                          (reset! value-ref val)
                                          (let [[r g b] (uc/hex->rgb val)
                                                [h s v] (uc/hex->hsv val)]
                                            (swap! current-color assoc
                                                   :r r :g g :b b
                                                   :h h :s s :v v)
                                            (on-change val (:alpha @current-color))))))}]
      [:input.red-value {:id "red-value"
                         :type "number"
                         :min 0
                         :max 255
                         :value (:r @current-color)
                         :on-change (fn [e]
                                      (let [val (-> e dom/get-target dom/get-value (math/clamp 0 255))]
                                        (swap! current-color assoc :r val)
                                        (when (not (nil? val))
                                          (let [{:keys [g b]} @current-color
                                                hex (uc/rgb->hex [val g b])
                                                [h s v] (uc/hex->hsv hex)]
                                            (reset! value-ref hex)
                                            (swap! current-color assoc
                                                   :hex hex
                                                   :h h :s s :v v)
                                            (on-change hex (:alpha @current-color))))))}]
      [:input.green-value {:id "green-value"
                           :type "number"
                           :min 0
                           :max 255
                           :value (:g @current-color)
                           :on-change (fn [e]
                                        (let [val (-> e dom/get-target dom/get-value (math/clamp 0 255))]
                                          (swap! current-color assoc :g val)
                                          (when (not (nil? val))
                                            (let [{:keys [r b]} @current-color
                                                  hex (uc/rgb->hex [r val b])
                                                  [h s v] (uc/hex->hsv hex)]
                                              (reset! value-ref hex)
                                              (swap! current-color assoc
                                                     :hex hex
                                                     :h h :s s :v v)
                                              (on-change hex (:alpha @current-color))))))}]
      [:input.blue-value {:id "blue-value"
                          :type "number"
                          :min 0
                          :max 255
                          :value (:b @current-color)
                          :on-change (fn [e]
                                       (let [val (-> e dom/get-target dom/get-value (math/clamp 0 255))]
                                         (swap! current-color assoc :b val)
                                         (when (not (nil? val))
                                           (let [{:keys [r g]} @current-color
                                                 hex (uc/rgb->hex [r g val])
                                                 [h s v] (uc/hex->hsv hex)]
                                             (reset! value-ref hex)
                                             (swap! current-color assoc
                                                    :hex hex
                                                    :h h :s s :v v)
                                             (on-change hex (:alpha @current-color))))))}]
      [:input.alpha-value {:id "alpha-value"
                           :type "number"
                           :min 0
                           :step 0.1
                           :max 1
                           :value (math/precision (:alpha @current-color) 2)
                           :on-change (fn [e]
                                        (let [val (-> e dom/get-target dom/get-value (math/clamp 0 1))]
                                          (swap! current-color assoc :alpha val)
                                          (on-change (:hex @current-color) val)))}]
      [:label.hex-label {:for "hex-value"} "HEX"]
      [:label.red-label {:for "red-value"} "R"]
      [:label.green-label {:for "green-value"} "G"]
      [:label.blue-label {:for "blue-value"} "B"]
      [:label.alpha-label {:for "alpha-value"} "A"]]

     [:div.libraries
      [:select {:on-change (fn [e]
                             (let [val (-> e dom/get-target dom/get-value)]
                               (reset! selected-library val)))
                :value @selected-library} 
       [:option {:value "recent"} "Recent colors"]
       [:option {:value "file"} "File library"]
       (for [[_ {:keys [name id]}] shared-libs]
         [:option {:key id
                   :value id} name])]

      [:div.selected-colors
       (when (= "file" @selected-library)
         [:div.color-bullet.button.plus-button {:style {:background-color "white"}
                                                :on-click #(st/emit! (dwl/add-color (:hex @current-color)))}
          i/plus])

       [:div.color-bullet.button {:style {:background-color "white"}}
        i/palette]

       (for [[idx color] (map-indexed vector @current-library-colors)]
         [:div.color-bullet {:key (str "color-" idx)
                             :on-click (fn []
                                         (swap! current-color assoc :hex color)
                                         (reset! value-ref color)
                                         (let [[r g b] (uc/hex->rgb color)
                                               [h s v] (uc/hex->hsv color)]
                                           (swap! current-color assoc
                                                  :r r :g g :b b
                                                  :h h :s s :v v)
                                           (on-change color (:alpha @current-color))))
                             :style {:background-color color}}])]

      ]
     (when on-accept
       [:div.actions
        [:button.btn-primary.btn-large
         {:on-click (fn []
                      (on-accept @value-ref)
                      (modal/hide!))}
         "Save color"]])])
  )

(mf/defc colorpicker-modal
  [{:keys [x y default value opacity page on-change disable-opacity position on-accept] :as props}]
  (let [position (or position :left)
        style (case position
                :left {:left (str (- x 270) "px")
                       :top (str (- y 50) "px")}
                :right {:left (str (+ x 24) "px")
                        :top (str (- y 50) "px")})]
    [:div.modal-overlay.transparent
     [:div.colorpicker-tooltip
      {:style (clj->js style)}
      #_[:& cp/colorpicker {:value (or value default)
                            :opacity (or opacity 1)
                            :colors (into-array @cp/most-used-colors)
                            :on-change on-change
                            :disable-opacity disable-opacity}]
      [:& colorpicker {:value (or value default)
                       :opacity (or opacity 1)
                       :on-change on-change
                       :on-accept on-accept
                       :disable-opacity disable-opacity}]]]))
