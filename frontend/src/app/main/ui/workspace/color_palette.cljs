;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.color-palette
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.color :as ctc]
   [app.common.types.library :as ctl]
   [app.main.data.event :as ev]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.strings :refer [matches-search]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc palette-item*
  {::mf/wrap [mf/memo]}
  [{:keys [color size selected]}]
  (let [select-color
        (mf/use-fn
         (mf/deps color selected)
         (fn [event]
           (st/emit! (mdc/add-recent-color color)
                     (mdc/apply-color-from-palette color (kbd/alt? event))
                     (when (not= selected :recent)
                       (ev/event
                        {::ev/name "use-library-color"
                         ::ev/origin "color-palette"
                         :external-library (not= selected :file)})))))
        title
        (uc/get-color-name color)]

    [:button {:class (stl/css-case
                      :color-cell true
                      :is-not-library-color (nil? (:id color))
                      :no-text (<= size 64))
              :title title
              :aria-label title
              :type "button"
              :on-click select-color}
     [:> swatch* {:background color :size "medium"}]
     [:> cb/color-name* {:color color :size size :origin :palette}]]))

(mf/defc palette*
  {::mf/wrap [mf/memo]}
  [{:keys [colors size width selected]}]
  (let [state        (mf/use-state #(do {:show-menu false}))
        search-term* (mf/use-state "")
        search-term  (deref search-term*)
        search-open* (mf/use-state false)
        search-open? (deref search-open*)
        has-colors?  (seq colors)

        filtered-colors
        (mf/with-memo [colors search-term]
          (if (empty? search-term)
            colors
            (filterv #(matches-search (or (uc/get-color-name %) "") search-term)
                     colors)))

        on-search-change
        (mf/use-fn #(reset! search-term* %))

        on-toggle-search
        (mf/use-fn
         (fn [_]
           (when @search-open*
             (reset! search-term* ""))
           (swap! search-open* not)))

        on-search-clear
        (mf/use-fn
         (fn [_]
           (reset! search-term* "")
           (reset! search-open* false)))

        ;; Everything below is expressed in real (rendered) pixels: the swatch
        ;; sizes and the horizontal paging math are all multiplied by `ui-scale`
        ;; so they stay coupled with the (measured, already-scaled) `width`.
        ui-scale     (mf/deref refs/ui-scale)

        offset-step  (* ui-scale
                        (cond
                          (<= size 64) 40
                          (<= size 80) 72
                          :else 72))
        ;; Reserve room for the search bar, icon button, or nothing
        search-width   (cond (not has-colors?) 0
                             search-open? 192
                             :else 32)
        buttons-size (* ui-scale
                        (cond
                          (<= size 64) (+ 164 search-width)
                          :else (+ 132 search-width)))
        width          (- width buttons-size)
        visible        (int (/ width offset-step))
        show-arrows?   (> (count filtered-colors) visible)
        visible        (if show-arrows?
                         (int (/ (- width (* ui-scale 48)) offset-step))
                         visible)
        offset         (:offset @state 0)
        max-offset     (- (count filtered-colors)
                          visible)
        container      (mf/use-ref nil)
        bullet-size  (* ui-scale
                        (cond
                          (<= size 64) 32
                          (<= size 72) 28
                          (<= size 80) 32
                          :else 32))
        color-cell-width (* ui-scale
                            (cond
                              (<= size 64) 32
                              :else 64))

        on-left-arrow-click
        (mf/use-fn
         (mf/deps max-offset visible)
         (fn [_]
           (swap! state update :offset
                  (fn [offset]
                    (if (pos? offset)
                      (max (- offset 1) 0)
                      offset)))))

        on-right-arrow-click
        (mf/use-fn
         (mf/deps max-offset visible)
         (fn [_]
           (swap! state update :offset
                  (fn [offset]
                    (if (< offset max-offset)
                      (min max-offset (+ offset 1))
                      offset)))))

        on-scroll
        (mf/use-fn
         (mf/deps max-offset)
         (fn [event]
           (let [event (dom/event->native-event event)
                 delta (+ (.. ^js event -deltaY)
                          (.. ^js event -deltaX))]
             (if (pos? delta)
               (on-right-arrow-click event)
               (on-left-arrow-click event)))))]

    (mf/with-layout-effect []
      (let [dom   (mf/ref-val container)
            width (obj/get dom "clientWidth")]
        (swap! state assoc :width width)))

    (mf/with-effect [width filtered-colors]
      (when (not= 0 (:offset @state))
        (swap! state assoc :offset 0)))

    (mf/with-effect [has-colors?]
      (when-not has-colors?
        (reset! search-open* false)
        (reset! search-term* "")))

    [:div {:class (stl/css-case
                   :color-palette true
                   :no-text (< size 64))
           :style #js {"--bullet-size" (dm/str bullet-size "px")
                       "--color-cell-width" (dm/str color-cell-width "px")}}

     (when has-colors?
       [:div {:class (stl/css-case :palette-search search-open?
                                   :palette-search-collapsed (not search-open?))}
        (when search-open?
          [:> search-bar* {:on-change on-search-change
                           :on-clear on-search-clear
                           :value search-term
                           :placeholder (tr "workspace.assets.search")
                           :auto-focus true}])
        [:> icon-button* {:variant "ghost"
                          :icon i/search
                          :on-click on-toggle-search
                          :aria-label (tr "workspace.assets.search")}]])

     (when show-arrows?
       [:button {:class (stl/css :left-arrow)
                 :disabled (= offset 0)
                 :on-click on-left-arrow-click} deprecated-icon/arrow])
     [:div {:class (stl/css :color-palette-content)
            :ref container
            :on-wheel on-scroll}
      (if (empty? filtered-colors)
        [:div {:class  (stl/css :color-palette-empty)
               :style {:position "absolute"
                       :left "50%"
                       :top "50%"
                       :transform "translate(-50%, -50%)"}}
         (if (empty? search-term)
           (tr "workspace.libraries.colors.empty-palette")
           (tr "workspace.assets.not-found"))]
        [:div {:class  (stl/css :color-palette-inside)
               :style {:position "relative"
                       :max-width (str width "px")
                       :right (str (* offset-step offset) "px")}}
         (for [[idx item] (map-indexed vector filtered-colors)]
           [:> palette-item* {:color item :key idx :size size :selected selected}])])]

     (when show-arrows?
       [:button {:class (stl/css :right-arrow)
                 :disabled (= offset max-offset)
                 :on-click on-right-arrow-click} deprecated-icon/arrow])]))

(mf/defc recent-colors-palette*
  {::mf/private true}
  [props]
  (let [libraries  (mf/deref refs/files)
        colors     (mf/deref refs/recent-colors)
        colors     (mf/with-memo [colors libraries]
                     (->> (reverse colors)
                          (filter ctc/valid-color?)
                          (map (fn [{:keys [ref-id ref-file] :as color}]
                                 ;; For make the UI consistent we need to ensure that a
                                 ;; library color looks exactly as it is actually and not
                                 ;; how it was saved first time
                                 (if (and ref-id ref-file)
                                   (let [fdata (dm/get-in libraries [ref-file :data])]
                                     ;; FIXME: get a direct helper for obtain plain color
                                     (or (some-> (ctl/get-color fdata ref-id)
                                                 (ctc/library-color->color ref-file))
                                         (dissoc color :ref-id :ref-file)))
                                   color)))
                          (vec)))

        props  (mf/spread-props props {:colors colors})]

    [:> palette* props]))

(defn- make-library-colors-ref
  [file-id]
  (l/derived (fn [files]
               (dm/get-in files [file-id :data :colors]))
             refs/files))

(mf/defc file-color-palette*
  {::mf/private true}
  [{:keys [file-id] :as props}]
  (let [colors-ref (mf/with-memo [file-id]
                     (make-library-colors-ref file-id))
        colors     (mf/deref colors-ref)
        colors     (mf/with-memo [colors file-id]
                     (->> (vals colors)
                          (filter ctc/valid-library-color?)
                          (sort-by :name)
                          (map #(ctc/library-color->color % file-id))
                          (vec)))
        props      (mf/spread-props props {:colors colors})]

    [:> palette* props]))

(mf/defc color-palette*
  {::mf/wrap [mf/memo]}
  [{:keys [selected] :as props}]
  (let [file-id (mf/use-ctx ctx/current-file-id)]
    (cond
      (= selected :recent)
      [:> recent-colors-palette* props]

      (= selected :file)
      (let [props (mf/spread-props props {:file-id file-id})]
        [:> file-color-palette* props])

      :else
      (let [props (mf/spread-props props {:file-id selected})]
        [:> file-color-palette* props]))))
