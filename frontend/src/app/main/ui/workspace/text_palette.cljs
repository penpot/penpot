;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.text-palette
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as f]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc typography-item
  [{:keys [file-id selected-ids typography name-only? size]}]
  (let [font-data (f/get-font-data (:font-id typography))
        font-variant-id (:font-variant-id typography)
        variant-data (->> font-data :variants (d/seek #(= (:id %) font-variant-id)))

        handle-click
        (mf/use-callback
         (mf/deps typography selected-ids)
         (fn []
           (let [attrs (merge
                        {:typography-ref-file file-id
                         :typography-ref-id (:id typography)}
                        (dissoc typography :id :name))]

             (run! #(st/emit!
                     (dwt/update-text-attrs
                      {:id %
                       :editor (get @refs/workspace-editor-state %)
                       :attrs attrs}))
                   selected-ids))))]
    [:div {:on-click handle-click
           :class (dom/classnames (css :typography-item) true
                                  (css :mid-item) (<= size 72)
                                  (css :small-item) (<= size 64))}
     [:div
      {:class (dom/classnames (css :typography-name) true)
       :title (:name typography)
       :style {:font-family (:font-family typography)
               :font-weight (:font-weight typography)
               :font-style (:font-style typography)}}
      (:name typography)]
     (when-not name-only?
       [:*
        [:div {:class (dom/classnames (css :typography-font) true)}
         (:name font-data)]
        [:div {:class (dom/classnames (css :typography-data) true)}
         (str (:font-size typography) "px | " (:name variant-data))]])]))

(mf/defc palette
  [{:keys [selected selected-ids current-file-id file-typographies shared-libs size width]}]
  (let [file-id
        (case selected
          :recent nil
          :file current-file-id
          selected)

        current-typographies
        (case selected
          :recent []
          :file (sort-by #(str/lower (:name %)) (vals file-typographies))
          (sort-by #(str/lower (:name %)) (vals (get-in shared-libs [selected :data :typographies]))))
        state (mf/use-state {:offset 0})
        offset-step 144
        buttons-size (cond
                       (<= size 64) 164
                       (<= size 72) 164
                       (<= size 80) 132
                       :else 132)
        width          (- width buttons-size)
        visible        (int (/ width offset-step))
        show-arrows?   (> (count current-typographies) visible)
        offset         (:offset @state 0)
        max-offset     (- (count current-typographies)
                          visible)
        container (mf/use-ref nil)


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

        on-wheel
        (mf/use-callback
         (mf/deps max-offset)
         (fn [event]
           (let [delta (+ (.. event -nativeEvent -deltaY) (.. event -nativeEvent -deltaX))]
             (if (pos? delta)
               (on-right-arrow-click event)
               (on-left-arrow-click event)))))]

    (mf/use-effect
     (mf/deps current-typographies)
     (fn []
       (let [fonts (into #{} (keep :font-id) current-typographies)]
         (run! f/ensure-loaded! fonts))))

    (mf/use-layout-effect
     #(let [dom   (mf/ref-val container)
            width (obj/get dom "clientWidth")]
        (swap! state assoc :width width)))

    (mf/with-effect [width selected]
      (when (not= 0 (:offset @state))
        (swap! state assoc :offset 0)))

    [:div {:class (dom/classnames (css :text-palette) true)
           :style #js {"--height" (str size "px")}}
     
     (when show-arrows?
       [:button {:class (dom/classnames (css :left-arrow) true)
                 :disabled (= offset 0)
                 :on-click on-left-arrow-click} i/arrow-refactor])

     [:div {:class (dom/classnames (css :text-palette-content) true)
            :ref container
            :on-wheel on-wheel}
      (if (empty? current-typographies)
        [:div {:class (dom/classnames (css :text-palette-empty) true)
               :style {:position "absolute"
                       :left "50%"
                       :top "50%"
                       :transform "translate(-50%, -50%)"}}
         (tr "workspace.libraries.colors.empty-typography-palette")]
        [:div
         {:class (dom/classnames  (css :text-palette-inside) true)
          :style {:position "relative"
                  :max-width (str width "px")
                  :right (str (* offset-step offset) "px")}}
         (for [[idx item] (map-indexed vector current-typographies)]
           [:& typography-item
            {:key idx
             :file-id file-id
             :selected-ids selected-ids
             :typography item
             :size size}])])]

     (when show-arrows?
       [:button {:class (dom/classnames (css :right-arrow) true)
                 :disabled (= offset max-offset)
                 :on-click on-right-arrow-click} i/arrow-refactor])]))

(mf/defc text-palette
  {::mf/wrap [mf/memo]}
  [{:keys [size width selected] :as props}]
  (let [selected-ids      (mf/deref refs/selected-shapes)
        file-typographies (mf/deref refs/workspace-file-typography)
        shared-libs       (mf/deref refs/workspace-libraries)
        current-file-id   (mf/use-ctx ctx/current-file-id)]
    [:& palette {:current-file-id current-file-id
                 :selected-ids selected-ids
                 :file-typographies file-typographies
                 :shared-libs shared-libs
                 :width width
                 :selected selected
                 :size size}]))
