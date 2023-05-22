;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.color-palette
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet-new :as cb]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc palette-item
  {::mf/wrap [mf/memo]}
  [{:keys [color size]}]
  (letfn [(select-color [event]
            (st/emit! (mdc/apply-color-from-palette color (kbd/alt? event))))]
    [:div {:class (dom/classnames (css :color-cell) true
                                  (css :is-not-library-color) (nil? (:id color))
                                  (css :no-text) (<= size 64))
           :title (uc/get-color-name color)
           :on-click select-color}
     [:& cb/color-bullet {:color color}]
     [:& cb/color-name {:color color :size size}]]))


(mf/defc palette
  [{:keys [current-colors size width]}]
  (let [;; We had to do this due to a bug that leave some bugged colors
        current-colors (h/use-equal-memo (filter #(or (:gradient %) (:color %)) current-colors))
        state          (mf/use-state {:show-menu false})
        offset-step (cond
                      (<= size 64) 40
                      (<= size 72) 88
                      (<= size 80) 88
                      :else 88)
        buttons-size (cond
                       (<= size 64) 164
                       (<= size 72) 164
                       (<= size 80) 132
                       :else 132)
        width          (- width buttons-size)
        visible        (int (/ width offset-step))
        show-arrows?   (> (count current-colors) visible)
        offset         (:offset @state 0)
        max-offset     (- (count current-colors)
                          visible)

        container      (mf/use-ref nil)
        bullet-size  (cond
                       (<= size 64) "32"
                       (<= size 72) "28"
                       (<= size 80) "32"
                       :else "32")

        on-left-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [_]
           (swap! state update :offset
                  (fn [offset]
                    (if (pos? offset)
                      (max (- offset 1) 0)
                      offset)))))

        on-right-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [_]
           (swap! state update :offset
                  (fn [offset]
                    (if (< offset max-offset)
                      (min max-offset (+ offset 1))
                      offset)))))

        on-scroll
        (mf/use-callback
         (mf/deps max-offset)
         (fn [event]
           (let [delta (+ (.. event -nativeEvent -deltaY) (.. event -nativeEvent -deltaX))]
             (if (pos? delta)
               (on-right-arrow-click event)
               (on-left-arrow-click event)))))]

    (mf/use-layout-effect
     #(let [dom   (mf/ref-val container)
            width (obj/get dom "clientWidth")]
        (swap! state assoc :width width)))

    (mf/with-effect [width current-colors]
      (when (not= 0 (:offset @state))
        (swap! state assoc :offset 0)))

    [:div {:class (dom/classnames (css :color-palette) true
                                  (css :no-text) (< size 64))
           :style #js {"--bullet-size" (dm/str bullet-size "px")}}

     (when show-arrows?
       [:button {:class (dom/classnames (css :left-arrow) true)
                 :disabled (= offset 0)
                 :on-click on-left-arrow-click} i/arrow-refactor])
     [:div {:class  (dom/classnames (css :color-palette-content) true)
            :ref container
            :on-wheel on-scroll}
      (if (empty? current-colors)
        [:div {:class  (dom/classnames (css :color-palette-empty) true)
               :style {:position "absolute"
                       :left "50%"
                       :top "50%"
                       :transform "translate(-50%, -50%)"}}
         (tr "workspace.libraries.colors.empty-palette")]
        [:div {:class  (dom/classnames (css :color-palette-inside) true)
               :style {:position "relative"
                       :max-width (str width "px")
                       :right (str (* offset-step offset) "px")}}
         (for [[idx item] (map-indexed vector current-colors)]
           [:& palette-item {:color item :key idx :size size}])])]
     (when show-arrows?
       [:button {:class (dom/classnames (css :right-arrow) true)
                 :disabled (= offset max-offset)
                 :on-click on-right-arrow-click} i/arrow-refactor])]))

(defn library->colors [shared-libs selected]
  (map #(merge % {:file-id selected})
       (-> shared-libs
           (get-in [selected :data :colors])
           (vals))))

(mf/defc color-palette
  {::mf/wrap [mf/memo]}
  [{:keys [size width selected] :as props}]
  (let [recent-colors (mf/deref refs/workspace-recent-colors)
        file-colors   (mf/deref refs/workspace-file-colors)
        shared-libs   (mf/deref refs/workspace-libraries)
        colors        (mf/use-state [])]

    (mf/with-effect [selected]
      (let [colors' (cond
                      (= selected :recent) (reverse recent-colors)
                      (= selected :file)   (->> (vals file-colors) (sort-by :name))
                      :else                 (->> (library->colors shared-libs selected) (sort-by :name)))]
        (reset! colors (into [] colors'))))

    (mf/with-effect [recent-colors selected]
      (when (= selected :recent)
        (reset! colors (reverse recent-colors))))

    (mf/with-effect [file-colors selected]
      (when (= selected :file)
        (reset! colors (into [] (->> (vals file-colors)
                                     (sort-by :name))))))

    [:& palette {:current-colors @colors
                 :size size
                 :width width}]))
