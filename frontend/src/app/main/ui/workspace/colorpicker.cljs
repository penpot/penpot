;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.config :as cfg]
   [app.main.data.event :as-alias ev]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as dsc]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.ds.foundations.assets.icon :as ic]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.colorpicker.color-inputs :refer [color-inputs]]
   [app.main.ui.workspace.colorpicker.gradients :refer [gradients*]]
   [app.main.ui.workspace.colorpicker.harmony :refer [harmony-selector]]
   [app.main.ui.workspace.colorpicker.hsva :refer [hsva-selector]]
   [app.main.ui.workspace.colorpicker.libraries :refer [libraries]]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector*]]
   [app.main.ui.workspace.colorpicker.shortcuts :as sc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
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

(defn opacity->string
  [opacity]
  (if (not= opacity :multiple)
    (dm/str (-> opacity
                (d/coalesce 1)
                (* 100)))
    :multiple))

;; --- Color Picker Modal

(defn use-color-picker-css-variables! [node-ref current-color]
  (mf/with-effect [current-color]
    (let [node (mf/ref-val node-ref)
          {:keys [r g b h v]} current-color
          rgb [r g b]
          hue-rgb (cc/hsv->rgb [h 1.0 255])
          hsl-from (cc/hsv->hsl [h 0.0 v])
          hsl-to (cc/hsv->hsl [h 1.0 v])

          format-hsl (fn [[h s l]]
                       (str/fmt "hsl(%s, %s, %s)"
                                h
                                (str (* s 100) "%")
                                (str (* l 100) "%")))]
      (dom/set-css-property! node "--color" (str/join ", " rgb))
      (dom/set-css-property! node "--hue-rgb" (str/join ", " hue-rgb))
      (dom/set-css-property! node "--saturation-grad-from" (format-hsl hsl-from))
      (dom/set-css-property! node "--saturation-grad-to" (format-hsl hsl-to)))))

(mf/defc colorpicker
  {::mf/props :obj}
  [{:keys [data disable-gradient disable-opacity disable-image on-change on-accept]}]
  (let [state                  (mf/deref refs/colorpicker)
        node-ref               (mf/use-ref)

        should-update?         (mf/use-var true)

        ;; TODO: I think we need to put all this picking state under
        ;; the same object for avoid creating adhoc refs for each
        ;; value
        picking-color?         (mf/deref picking-color?)
        picked-color           (mf/deref picked-color)
        picked-color-select    (mf/deref picked-color-select)

        current-color          (:current-color state)

        active-fill-tab        (if (:image data)
                                 :image
                                 (if-let [gradient (:gradient data)]
                                   (case (:type gradient)
                                     :linear :linear-gradient
                                     :radial :radial-gradient)
                                   :color))
        active-color-tab       (mf/use-state #(dc/get-active-color-tab))
        drag?*                 (mf/use-state false)
        drag?                  (deref drag?*)

        type                   (if (= @active-color-tab "hsva") :hsv :rgb)

        fill-image-ref         (mf/use-ref nil)

        color-type             (get state :type :color)
        selected-mode          (case color-type
                                 (:linear-gradient :radial-gradient)
                                 :gradient

                                 color-type)

        disabled-color-accept? (and
                                (= selected-mode :image)
                                (not (:image current-color)))

        on-fill-image-success
        (mf/use-fn
         (fn [image]
           (st/emit! (dc/update-colorpicker-color
                      {:image (-> (select-keys image [:id :width :height :mtype :name])
                                  (assoc :keep-aspect-ratio true))}
                      (not drag?)))))

        on-fill-image-click
        (mf/use-fn #(dom/click (mf/ref-val fill-image-ref)))

        on-fill-image-selected
        (mf/use-fn
         (fn [file]
           (st/emit! (dwm/upload-fill-image file on-fill-image-success))))

        handle-change-keep-aspect-ratio
        (mf/use-fn
         (mf/deps current-color)
         (fn []
           (let [keep-aspect-ratio? (-> current-color :image :keep-aspect-ratio not)
                 image              (-> (:image current-color)
                                        (assoc :keep-aspect-ratio keep-aspect-ratio?))]

             (st/emit!
              (dc/update-colorpicker-color {:image image} true)
              (ptk/data-event ::ev/event {::ev/name "toggle-image-aspect-ratio"
                                          ::ev/origin "workspace:colorpicker"
                                          :checked keep-aspect-ratio?})))))

        on-change-tab
        (mf/use-fn
         (fn [tab]
           (reset! active-color-tab tab)
           (dc/set-active-color-tab! tab)))

        handle-change-mode
        (mf/use-fn
         (fn [value]
           (case value
             :color (st/emit! (dc/activate-colorpicker-color))
             :gradient (st/emit! (dc/activate-colorpicker-gradient :linear-gradient))
             :image (st/emit! (dc/activate-colorpicker-image))
             nil)))

        handle-change-color
        (mf/use-fn
         (mf/deps current-color drag?)
         (fn [color]
           (let [color (merge current-color color)
                 color (dc/materialize-color-components color)]
             (st/emit! (dc/update-colorpicker-color color (not drag?))))))

        handle-click-picker
        (mf/use-fn
         (mf/deps picking-color?)
         (fn []
           (if picking-color?
             (do (modal/disallow-click-outside!)
                 (st/emit! (dc/stop-picker)))
             (do (modal/allow-click-outside!)
                 (st/emit! (dc/start-picker))))))

        on-select-library-color
        (mf/use-fn
         (mf/deps data handle-change-color)
         (fn [_ color]
           (if (and (some? (:color color)) (some? (:gradient data)))
             (handle-change-color {:hex (:color color) :alpha (:opacity color)})
             (do
               (st/emit!
                (dwl/add-recent-color color)
                (dc/apply-color-from-colorpicker color))
               (on-change color)))))

        on-add-library-color
        (mf/use-fn
         (mf/deps state)
         (fn [_]
           (st/emit! (dwl/add-color (dc/get-color-from-colorpicker-state state)))))

        on-start-drag
        (mf/use-fn
         (mf/deps drag?* node-ref)
         (fn []
           (reset! should-update? false)
           (reset! drag?* true)
           (st/emit! (dwu/start-undo-transaction (mf/ref-val node-ref)))))

        on-finish-drag
        (mf/use-fn
         (mf/deps drag?* node-ref)
         (fn []
           (reset! should-update? true)
           (reset! drag?* false)
           (st/emit! (dwu/commit-undo-transaction (mf/ref-val node-ref)))))

        on-color-accept
        (mf/use-fn
         (mf/deps state)
         (fn []
           (on-accept (dc/get-color-from-colorpicker-state state))
           (modal/hide!)))

        options
        (mf/with-memo [selected-mode disable-gradient disable-image]
          (d/concat-vec
           [{:value :color :label (tr "media.solid")}]
           (when (not disable-gradient)
             [{:value :gradient :label (tr "media.gradient")}])
           (when (not disable-image)
             [{:value :image :label (tr "media.image")}])))

        handle-change-gradient-selected-stop
        (mf/use-fn
         (fn [index]
           (st/emit! (dc/select-colorpicker-gradient-stop index))))

        handle-change-gradient-type
        (mf/use-fn
         (fn [type]
           (st/emit! (dc/activate-colorpicker-gradient type))))

        handle-gradient-change-stop
        (mf/use-fn
         (mf/deps state)
         (fn [prev-stop new-stop]
           (let [stops (->> (:stops state)
                            (mapv #(if (= % prev-stop) new-stop %)))]
             (st/emit! (dc/update-colorpicker-stops stops)))))

        handle-gradient-add-stop-auto
        (mf/use-fn
         (fn []
           (st/emit! (dc/update-colorpicker-add-auto))))

        handle-gradient-add-stop-preview
        (mf/use-fn
         (fn [offset]
           (st/emit! (dc/update-colorpicker-add-stop offset))))

        handle-gradient-remove-stop
        (mf/use-fn
         (mf/deps state)
         (fn [stop]
           (when (> (count (:stops state)) 2)
             (when-let [index (d/index-of-pred (:stops state) #(= % stop))]
               (st/emit! (dc/remove-gradient-stop index))))))

        handle-stop-edit-start
        (mf/use-fn
         (fn []
           (reset! should-update? false)))

        handle-stop-edit-finish
        (mf/use-fn
         (mf/deps state)
         (fn []
           (reset! should-update? true)

           ;; Update asynchronously so we can update with the new stops
           (ts/schedule #(st/emit! (dc/sort-colorpicker-stops)))))

        handle-rotate-stops
        (mf/use-fn
         (mf/deps state)
         (fn []
           (let [gradient (:gradient state)
                 mtx (gmt/rotate-matrix 90 (gpt/point 0.5 0.5))

                 start-p (-> (gpt/point (:start-x gradient) (:start-y gradient))
                             (gpt/transform mtx))

                 end-p (-> (gpt/point (:end-x gradient) (:end-y gradient))
                           (gpt/transform mtx))]
             (st/emit! (dc/update-colorpicker-gradient {:start-x (:x start-p)
                                                        :start-y (:y start-p)
                                                        :end-x (:x end-p)
                                                        :end-y (:y end-p)})))))

        handle-reverse-stops
        (mf/use-fn
         (mf/deps (:stops state))
         (fn []
           (let [stops (->> (:stops state)
                            (map (fn [it] (update it :offset #(+ 1 (* -1 %)))))
                            (sort-by :offset)
                            (into []))]
             (st/emit! (dc/update-colorpicker-stops stops)))))

        handle-reorder-stops
        (mf/use-fn
         (mf/deps (:stops state))
         (fn [from-index to-index]
           (let [stops (:stops state)
                 new-stops
                 (-> stops
                     (d/insert-at-index to-index [(get stops from-index)]))
                 stops
                 (mapv #(assoc %2 :offset (:offset %1)) stops new-stops)]
             (st/emit! (dc/update-colorpicker-stops stops)))))

        handle-change-gradient-opacity
        (mf/use-fn
         (fn [value]
           (st/emit! (dc/update-colorpicker-gradient-opacity (/ value 100)))))

        tabs
        #js [#js {:aria-label (tr "workspace.libraries.colors.rgba")
                  :icon ic/rgba
                  :id "ramp"
                  :content (mf/html (if picking-color?
                                      [:div {:class (stl/css :picker-detail-wrapper)}
                                       [:div {:class (stl/css :center-circle)}]
                                       [:canvas#picker-detail {:class (stl/css :picker-detail) :width 256 :height 140}]]
                                      [:> ramp-selector*
                                       {:color current-color
                                        :disable-opacity disable-opacity
                                        :on-change handle-change-color
                                        :on-start-drag on-start-drag
                                        :on-finish-drag on-finish-drag}]))}

             #js {:aria-label "Harmony"
                  :icon ic/rgba-complementary
                  :id "harmony"
                  :content (mf/html (if picking-color?
                                      [:div {:class (stl/css :picker-detail-wrapper)}
                                       [:div {:class (stl/css :center-circle)}]
                                       [:canvas#picker-detail {:class (stl/css :picker-detail) :width 256 :height 140}]]
                                      [:& harmony-selector
                                       {:color current-color
                                        :disable-opacity disable-opacity
                                        :on-change handle-change-color
                                        :on-start-drag on-start-drag
                                        :on-finish-drag on-finish-drag}]))}

             #js {:aria-label "HSVA"
                  :icon ic/hsva
                  :id "hsva"
                  :content (mf/html (if picking-color?
                                      [:div {:class (stl/css :picker-detail-wrapper)}
                                       [:div {:class (stl/css :center-circle)}]
                                       [:canvas#picker-detail {:class (stl/css :picker-detail) :width 256 :height 140}]]
                                      [:& hsva-selector
                                       {:color current-color
                                        :disable-opacity disable-opacity
                                        :on-change handle-change-color
                                        :on-start-drag on-start-drag
                                        :on-finish-drag on-finish-drag}]))}]]

    ;; Initialize colorpicker state
    (mf/with-effect []
      (st/emit! (dc/initialize-colorpicker on-change active-fill-tab))
      (partial st/emit! (dc/finalize-colorpicker)))

    ;; Update colorpicker with external color changes
    (mf/with-effect [data]
      (when @should-update?
        (st/emit! (dc/update-colorpicker data))))

    ;; Updates the CSS color variable when there is a change in the color
    (use-color-picker-css-variables! node-ref current-color)

    ;; Updates color when pixel picker is used
    (mf/with-effect [picking-color? picked-color picked-color-select]
      (when (and picking-color? picked-color picked-color-select)
        (let [[r g b alpha] picked-color
              hex (cc/rgb->hex [r g b])
              [h s v] (cc/hex->hsv hex)]
          (handle-change-color {:hex hex
                                :r r :g g :b b
                                :h h :s s :v v
                                :alpha (/ alpha 255)}))))

    [:*
     [:div {:class (stl/css :colorpicker)
            :ref node-ref
            :style {:touch-action "none"}}
      [:div {:class (stl/css :top-actions)}
       [:div {:class (stl/css :top-actions-right)}
        (when (= :gradient selected-mode)
          [:div {:class (stl/css :opacity-input-wrapper)}
           [:span {:class (stl/css :icon-text)} "%"]
           [:> numeric-input*
            {:value (-> data :opacity opacity->string)
             :on-change handle-change-gradient-opacity
             :default 100
             :min 0
             :max 100}]])

        (when (or (not disable-gradient) (not disable-image))
          [:div {:class (stl/css :select)}
           [:& select
            {:default-value selected-mode
             :options options
             :on-change handle-change-mode}]])]

       (when (not= selected-mode :image)
         [:button {:class (stl/css-case :picker-btn true
                                        :selected picking-color?)
                   :on-click handle-click-picker}
          i/picker])]

      (when (= selected-mode :gradient)
        [:> gradients*
         {:type (:type state)
          :stops (:stops state)
          :editing-stop (:editing-stop state)
          :on-stop-edit-start handle-stop-edit-start
          :on-stop-edit-finish handle-stop-edit-finish
          :on-select-stop handle-change-gradient-selected-stop
          :on-change-type handle-change-gradient-type
          :on-change-stop handle-gradient-change-stop
          :on-add-stop-auto handle-gradient-add-stop-auto
          :on-add-stop-preview handle-gradient-add-stop-preview
          :on-remove-stop handle-gradient-remove-stop
          :on-rotate-stops handle-rotate-stops
          :on-reverse-stops handle-reverse-stops
          :on-reorder-stops handle-reorder-stops}])

      (if (= selected-mode :image)
        (let [uri (cfg/resolve-file-media (:image current-color))
              keep-aspect-ratio? (-> current-color :image :keep-aspect-ratio)]
          [:div {:class (stl/css :select-image)}
           [:div {:class (stl/css :content)}
            (when (:image current-color)
              [:img {:src uri}])]

           (when (some? (:image current-color))
             [:div {:class (stl/css :checkbox-option)}
              [:label {:for "keep-aspect-ratio"
                       :class (stl/css-case  :global/checked keep-aspect-ratio?)}
               [:span {:class (stl/css-case :global/checked keep-aspect-ratio?)}
                (when keep-aspect-ratio?
                  i/status-tick)]
               (tr "media.keep-aspect-ratio")
               [:input {:type "checkbox"
                        :id "keep-aspect-ratio"
                        :checked keep-aspect-ratio?
                        :on-change handle-change-keep-aspect-ratio}]]])
           [:button
            {:class (stl/css :choose-image)
             :title (tr "media.choose-image")
             :aria-label (tr "media.choose-image")
             :on-click on-fill-image-click}
            (tr "media.choose-image")
            [:& file-uploader
             {:input-id "fill-image-upload"
              :accept "image/jpeg,image/png"
              :multi false
              :ref fill-image-ref
              :on-selected on-fill-image-selected}]]])
        [:*
         [:div {:class (stl/css :colorpicker-tabs)}
          [:> tab-switcher* {:tabs tabs
                             :default-selected "ramp"
                             :on-change-tab on-change-tab}]]

         [:& color-inputs
          {:type type
           :disable-opacity disable-opacity
           :color current-color
           :on-change handle-change-color}]

         [:& libraries
          {:state state
           :current-color current-color
           :disable-gradient disable-gradient
           :disable-opacity disable-opacity
           :disable-image disable-image
           :on-select-color on-select-library-color
           :on-add-library-color on-add-library-color}]])]
     (when (fn? on-accept)
       [:div {:class (stl/css :actions)}
        [:button {:class (stl/css-case
                          :accept-color true
                          :btn-disabled disabled-color-accept?)
                  :on-click on-color-accept
                  :disabled disabled-color-accept?}
         (tr "workspace.libraries.colors.save-color")]])]))

(defn calculate-position
  "Calculates the style properties for the given coordinates and position"
  [{vh :height} position x y gradient?]
  (let [;; picker size in pixels
        h (if gradient? 820 510)
        w 284
        ;; Checks for overflow outside the viewport height
        max-y   (- vh h)
        rulers? (mf/deref refs/rulers?)
        left-offset (if rulers? 40 18)
        right-offset (+ w 40)
        top-offset (dm/str (- y 70) "px")
        bottom-offset "1rem"
        max-height-top (str "calc(100vh - " top-offset)
        max-height-bottom (str "calc(100vh -" bottom-offset)]
    (cond
      (or (nil? x) (nil? y))
      #js {:left "auto" :right "16rem" :top "4rem" :maxHeight "calc(100vh - 4rem)"}

      (= position :left)
      (if (> y max-y)
        #js {:left (dm/str (- x right-offset) "px")
             :bottom bottom-offset
             :maxHeight max-height-bottom}
        #js {:left (dm/str (- x right-offset) "px")
             :top top-offset
             :maxHeight max-height-top})

      (= position :right)
      (if (> y max-y)
        #js {:left (dm/str (+ x 80) "px")
             :bottom bottom-offset
             :maxHeight max-height-bottom}
        #js {:left (dm/str (+ x 80) "px")
             :top top-offset
             :maxHeight max-height-top})

      :else
      (if (> y max-y)
        #js {:left (dm/str (+ x left-offset) "px")
             :bottom bottom-offset
             :maxHeight max-height-bottom}
        #js {:left (dm/str (+ x left-offset) "px")
             :top top-offset
             :maxHeight max-height-top}))))

(mf/defc colorpicker-modal
  {::mf/register modal/components
   ::mf/register-as :colorpicker
   ::mf/props :obj}
  [{:keys [x y data position
           disable-gradient
           disable-opacity
           disable-image
           on-change
           on-close
           on-accept]}]
  (let [vport       (mf/deref viewport)
        dirty?      (mf/use-var false)
        last-change (mf/use-var nil)
        position    (d/nilv position :left)
        style       (calculate-position vport position x y (some? (:gradient data)))

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [new-data]
           (reset! dirty? (not= data new-data))
           (when (not= new-data @last-change)
             (reset! last-change new-data)
             (if (fn? on-change)
               (on-change new-data)
               (st/emit! (dc/update-colorpicker new-data))))))]

    (mf/with-effect []
      (st/emit! (st/emit! (dsc/push-shortcuts ::colorpicker sc/shortcuts)))
      (fn []
        (st/emit! (dsc/pop-shortcuts ::colorpicker))
        (when (and @dirty? @last-change on-close)
          (on-close @last-change))))

    [:div {:class (stl/css :colorpicker-tooltip)
           :data-testid "colorpicker"
           :style style}

     [:& colorpicker {:data data
                      :disable-gradient disable-gradient
                      :disable-opacity disable-opacity
                      :disable-image disable-image
                      :on-change on-change'
                      :on-accept on-accept}]]))
