;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.color-palette
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.color :as ctc]
   [app.main.data.event :as ev]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
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
                       (ptk/data-event ::ev/event
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
     [:& cb/color-name {:color color :size size :origin :palette}]]))

(mf/defc palette*
  {::mf/wrap [mf/memo]}
  [{:keys [colors size width selected]}]
  (let [state        (mf/use-state #(do {:show-menu false}))
        offset-step  (cond
                       (<= size 64) 40
                       (<= size 80) 72
                       :else 72)
        buttons-size (cond
                       (<= size 64) 164
                       :else 132)
        width          (- width buttons-size)
        visible        (int (/ width offset-step))
        show-arrows?   (> (count colors) visible)
        visible        (if show-arrows?
                         (int (/ (- width 48) offset-step))
                         visible)
        offset         (:offset @state 0)
        max-offset     (- (count colors)
                          visible)
        container      (mf/use-ref nil)
        bullet-size  (cond
                       (<= size 64) "32"
                       (<= size 72) "28"
                       (<= size 80) "32"
                       :else "32")
        color-cell-width (cond
                           (<= size 64) 32
                           :else 64)

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

    (mf/with-effect [width colors]
      (when (not= 0 (:offset @state))
        (swap! state assoc :offset 0)))

    [:div {:class (stl/css-case
                   :color-palette true
                   :no-text (< size 64))
           :style #js {"--bullet-size" (dm/str bullet-size "px")
                       "--color-cell-width" (dm/str color-cell-width "px")}}

     (when show-arrows?
       [:button {:class (stl/css :left-arrow)
                 :disabled (= offset 0)
                 :on-click on-left-arrow-click} i/arrow])
     [:div {:class (stl/css :color-palette-content)
            :ref container
            :on-wheel on-scroll}
      (if (empty? colors)
        [:div {:class  (stl/css :color-palette-empty)
               :style {:position "absolute"
                       :left "50%"
                       :top "50%"
                       :transform "translate(-50%, -50%)"}}
         (tr "workspace.libraries.colors.empty-palette")]
        [:div {:class  (stl/css :color-palette-inside)
               :style {:position "relative"
                       :max-width (str width "px")
                       :right (str (* offset-step offset) "px")}}
         (for [[idx item] (map-indexed vector colors)]
           [:> palette-item* {:color item :key idx :size size :selected selected}])])]

     (when show-arrows?
       [:button {:class (stl/css :right-arrow)
                 :disabled (= offset max-offset)
                 :on-click on-right-arrow-click} i/arrow])]))

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
                                     (or (some-> (ctc/get-color fdata ref-id)
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
