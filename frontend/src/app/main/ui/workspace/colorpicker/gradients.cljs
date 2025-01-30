;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.gradients
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.reorder-handler :refer [reorder-handler]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn offset->string
  [opacity]
  (str (-> opacity
           (d/coalesce 1)
           (* 100)
           (fmt/format-number))))

(defn- event->offset
  [^js event]
  (/ (.. event -nativeEvent -offsetX)
     (-> event dom/get-current-target dom/get-bounding-rect :width)))

(defn- format-rgb
  [{:keys [r g b offset]}]
  (str/ffmt "rgb(%1, %2, %3) %4%%" r g b (* offset 100)))

(defn- gradient->string [stops]
  (->> stops
       (sort-by :offset)
       (map (fn [{:keys [color opacity offset]}]
              (let [[r g b] (cc/hex->rgb color)]
                {:r r :g g :b b :alpha opacity :offset offset})))
       (map format-rgb)
       (str/join ", ")
       (str/ffmt "linear-gradient(90deg, %1)")))

(mf/defc stop-input-row*
  {::mf/private true}
  [{:keys [stop
           index
           is-selected
           on-select-stop
           on-change-stop
           on-remove-stop
           on-reorder-stops
           on-focus-stop-offset
           on-blur-stop-offset
           on-focus-stop-color
           on-blur-stop-color]}]
  (let [offset (get stop :offset)

        handle-change-stop-color
        (mf/use-callback
         (mf/deps on-change-stop stop)
         (fn [value]
           (on-change-stop stop (merge stop value))))

        handle-change-offset
        (mf/use-callback
         (mf/deps on-change-stop stop)
         (fn [value]
           (on-change-stop stop (assoc stop :offset (mth/precision (/ value 100) 2)))))

        handle-remove-stop
        (mf/use-callback
         (mf/deps on-remove-stop stop)
         (fn []
           (when on-remove-stop
             (on-remove-stop stop))))

        handle-focus-stop-offset
        (mf/use-fn
         (mf/deps on-select-stop on-focus-stop-offset index)
         (fn []
           (on-select-stop index)
           (when on-focus-stop-offset
             (on-focus-stop-offset))))

        handle-blur-stop-offset
        (mf/use-fn
         (mf/deps on-select-stop on-blur-stop-offset index)
         (fn []
           (on-select-stop index)
           (when on-blur-stop-offset
             (on-blur-stop-offset))))

        handle-focus-stop-color
        (mf/use-fn
         (mf/deps on-select-stop on-focus-stop-offset index)
         (fn []
           (on-select-stop index)
           (when on-focus-stop-color
             (on-focus-stop-offset))))

        handle-blur-stop-color
        (mf/use-fn
         (mf/deps on-select-stop on-blur-stop-color index)
         (fn []
           (on-select-stop index)
           (when on-blur-stop-color
             (on-blur-stop-color))))

        on-drop
        (mf/use-fn
         (mf/deps index on-reorder-stops)
         (fn [position data]
           (let [from-index (:index data)
                 to-index (if (= position :bot) (inc index) index)]
             (when on-reorder-stops
               (on-reorder-stops from-index to-index)))))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/stops"
         :on-drop on-drop
         :data {:index index}
         :draggable? true)]

    [:div {:class (stl/css-case :gradient-stops-entry true
                                :is-selected is-selected
                                :dnd-over (= (:over dprops) :center)
                                :dnd-over-top (= (:over dprops) :top)
                                :dnd-over-bot (= (:over dprops) :bot))}

     [:& reorder-handler {:ref dref}]

     [:div {:class (stl/css :offset-input-wrapper)}
      [:span {:class (stl/css :icon-text)} "%"]
      [:> numeric-input*
       {:value (-> offset offset->string)
        :on-change handle-change-offset
        :default 100
        :min 0
        :max 100
        :on-focus handle-focus-stop-offset
        :on-blur handle-blur-stop-offset}]]

     [:> color-row*
      {:disable-gradient true
       :disable-picker true
       :color stop
       :index index
       :on-change handle-change-stop-color
       :on-remove handle-remove-stop
       :on-focus handle-focus-stop-color
       :on-blur handle-blur-stop-color}]]))

(mf/defc gradients*
  [{:keys [type
           stops
           editing-stop
           on-select-stop
           on-change-type
           on-change-stop
           on-add-stop-preview
           on-add-stop-auto
           on-remove-stop
           on-stop-edit-start
           on-stop-edit-finish
           on-reverse-stops
           on-rotate-stops
           on-reorder-stops]}]

  (let [preview-state  (mf/use-state #(do {:hover? false :offset 0.5}))
        dragging-ref   (mf/use-ref false)
        start-ref      (mf/use-ref nil)
        start-offset   (mf/use-ref nil)
        background-ref (mf/use-ref nil)

        handle-select-stop
        (mf/use-callback
         (mf/deps on-select-stop)
         (fn [event]
           (when on-select-stop
             (let [index (-> event dom/get-current-target (dom/get-data "index") d/read-string)]
               (on-select-stop index)))))

        handle-change-type
        (mf/use-callback
         (mf/deps on-change-type)
         (fn [event]
           (when on-change-type
             (on-change-type event))))

        handle-add-stop
        (mf/use-callback
         (mf/deps on-add-stop-auto)
         (fn []
           (when on-add-stop-auto
             (on-add-stop-auto))))

        handle-preview-enter
        (mf/use-fn
         (fn []
           (swap! preview-state assoc :hover? true)))

        handle-preview-leave
        (mf/use-fn
         (fn []
           (swap! preview-state assoc :hover? false)))

        handle-preview-move
        (mf/use-fn
         (fn [^js e]
           (let [offset (-> (event->offset e)
                            (mth/precision 2))]
             (swap! preview-state assoc :offset offset))))

        handle-preview-down
        (mf/use-fn
         (mf/deps on-add-stop-preview)
         (fn [^js e]
           (let [offset (-> (event->offset e)
                            (mth/precision 2))]
             (when on-add-stop-preview
               (on-add-stop-preview offset)))))

        handle-stop-marker-pointer-down
        (mf/use-fn
         (mf/deps on-stop-edit-start handle-select-stop stops)
         (fn [event]
           (let [index (-> event dom/get-current-target (dom/get-data "index") d/read-string)
                 stop (get stops index)]
             (dom/capture-pointer event)
             (handle-select-stop event)
             (mf/set-ref-val! dragging-ref true)
             (mf/set-ref-val! start-ref (dom/get-client-position event))
             (mf/set-ref-val! start-offset (:offset stop))
             (on-stop-edit-start))))

        handle-stop-marker-pointer-move
        (mf/use-fn
         (mf/deps on-change-stop stops)
         (fn [event]
           (when-let [_ (mf/ref-val dragging-ref)]
             (let [start-pt (mf/ref-val start-ref)
                   start-offset (mf/ref-val start-offset)

                   index (-> event dom/get-target (dom/get-data "index") d/read-string)
                   current-pt (dom/get-client-position event)
                   delta-x (- (:x current-pt) (:x start-pt))
                   background-node (mf/ref-val background-ref)
                   background-width (->  background-node dom/get-bounding-rect :width)

                   delta-offset (/ delta-x background-width)
                   stop (get stops index)

                   new-offset (mth/precision (mth/clamp (+ start-offset delta-offset) 0 1) 2)]
               (on-change-stop stop (assoc stop :offset new-offset))))))

        handle-stop-marker-lost-pointer-capture
        (mf/use-fn
         (mf/deps on-stop-edit-finish)
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! dragging-ref false)
           (mf/set-ref-val! start-ref nil)
           (on-stop-edit-finish)))

        handle-rotate-gradient
        (mf/use-fn
         (mf/deps on-rotate-stops)
         (fn []
           (when on-rotate-stops
             (on-rotate-stops))))

        handle-reverse-gradient
        (mf/use-fn
         (mf/deps on-reverse-stops)
         (fn []
           (when on-reverse-stops
             (on-reverse-stops))))]

    [:div {:class (stl/css :gradient-panel)}
     [:div {:class (stl/css :gradient-preview)}
      [:div {:class (stl/css :gradient-background)
             :ref background-ref
             :style {:background (gradient->string stops)}
             :on-pointer-enter handle-preview-enter
             :on-pointer-leave handle-preview-leave
             :on-pointer-move handle-preview-move
             :on-pointer-down handle-preview-down}
       [:div {:class (stl/css :gradient-preview-stop-preview)
              :style {:display (if (:hover? @preview-state) "block" "none")
                      "--preview-position" (dm/str (* 100 (:offset @preview-state)) "%")}}]]

      [:div {:class (stl/css :gradient-preview-stop-wrapper)}
       (for [[index {:keys [color offset r g b alpha]}] (d/enumerate stops)]
         [:* {:key (dm/str "preview-stop-" index)}
          [:div
           {:class (stl/css-case :gradient-preview-stop true
                                 :is-selected (= editing-stop index))
            :style {"--color-solid" color
                    "--color-alpha" (str/ffmt "rgba(%1, %2, %3, %4)" r g b alpha)
                    "--position" (dm/str (* offset 100) "%")}
            :data-index index

            :on-pointer-down handle-stop-marker-pointer-down
            :on-pointer-move handle-stop-marker-pointer-move
            :on-lost-pointer-capture handle-stop-marker-lost-pointer-capture}
           [:div {:class (stl/css :gradient-preview-stop-color)
                  :style {:pointer-events "none"}}]
           [:div {:class (stl/css :gradient-preview-stop-alpha)
                  :style {:pointer-events "none"}}]]
          [:div {:class (stl/css :gradient-preview-stop-decoration)
                 :style {"--position" (dm/str (* offset 100) "%")}}]])]]

     [:div {:class (stl/css :gradient-options)}
      [:& select
       {:default-value type
        :options [{:value :linear-gradient :label "Linear"}
                  {:value :radial-gradient :label "Radial"}]
        :on-change handle-change-type
        :class (stl/css :gradient-options-select)}]

      [:div {:class (stl/css :gradient-options-buttons)}
       [:> icon-button* {:variant "ghost"
                         :aria-label "Rotate gradient"
                         :on-click handle-rotate-gradient
                         :icon-class (stl/css :rotate-icon)
                         :icon "reload"}]
       [:> icon-button* {:variant "ghost"
                         :aria-label "Reverse gradient"
                         :on-click handle-reverse-gradient
                         :icon "switch"}]
       [:> icon-button* {:variant "ghost"
                         :aria-label "Add stop"
                         :on-click handle-add-stop
                         :icon "add"}]]]

     [:div {:class (stl/css :gradient-stops-list)}
      [:& h/sortable-container {}
       (for [[index stop] (d/enumerate stops)]
         [:> stop-input-row*
          {:key index
           :stop stop
           :index index
           :is-selected (= editing-stop index)
           :on-select-stop on-select-stop
           :on-change-stop on-change-stop
           :on-remove-stop on-remove-stop
           :on-focus-stop-offset on-stop-edit-start
           :on-blur-stop-offset on-stop-edit-finish
           :on-focus-stop-color on-stop-edit-start
           :on-blur-stop-color on-stop-edit-finish
           :on-reorder-stops on-reorder-stops}])]]

     [:hr {:class (stl/css :gradient-separator)}]]))
