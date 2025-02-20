;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.text-palette
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.event :as ev]
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
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc typography-item
  [{:keys [file-id selected-ids typography name-only? size current-file-id]}]
  (let [font-data (f/get-font-data (:font-id typography))
        font-variant-id (:font-variant-id typography)
        variant-data (->> font-data :variants (d/seek #(= (:id %) font-variant-id)))


        handle-click
        (mf/use-callback
         (mf/deps typography selected-ids file-id current-file-id)
         (fn []
           (let [attrs (merge
                        {:typography-ref-file file-id
                         :typography-ref-id (:id typography)}
                        (dissoc typography :id :name))]

             (st/emit! (ptk/event
                        ::ev/event
                        {::ev/name "use-library-typography"
                         ::ev/origin "text-palette"
                         :external-library (not= file-id current-file-id)}))
             (run! #(st/emit!
                     (dwt/update-text-attrs
                      {:id %
                       :editor (get @refs/workspace-editor-state %)
                       :attrs attrs}))
                   selected-ids))))]
    [:div {:on-click handle-click
           :class (stl/css-case :typography-item true
                                :mid-item (<= size 72)
                                :small-item (<= size 64))}
     [:div
      {:class (stl/css :typography-name)
       :title (:name typography)
       :style {:font-family (:font-family typography)
               :font-weight (:font-weight typography)
               :font-style (:font-style typography)}}
      (:name typography)]
     (when-not name-only?
       [:*
        [:div {:class (stl/css :typography-font)}
         (:name font-data)]
        [:div {:class (stl/css :typography-data)}
         (str (:font-size typography) "px | " (:name variant-data))]])]))

(mf/defc palette
  [{:keys [selected selected-ids current-file-id file-typographies libraries size width]}]
  (let [file-id
        (case selected
          :recent nil
          :file current-file-id
          selected)

        current-typographies
        (case selected
          :recent []
          :file (sort-by #(str/lower (:name %)) (vals file-typographies))
          (sort-by #(str/lower (:name %)) (vals (get-in libraries [selected :data :typographies]))))
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
           (let [event (dom/event->native-event event)
                 delta (+ (.. ^js event -deltaY)
                          (.. ^js event -deltaX))]
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

    [:div {:class (stl/css :text-palette)
           :style #js {"--height" (str size "px")}}
     (when show-arrows?
       [:button {:class (stl/css :left-arrow)
                 :disabled (= offset 0)
                 :on-click on-left-arrow-click} i/arrow])

     [:div {:class (stl/css :text-palette-content)
            :ref container
            :on-wheel on-wheel}
      (if (empty? current-typographies)
        [:div {:class (stl/css :text-palette-empty)
               :style {:position "absolute"
                       :left "50%"
                       :top "50%"
                       :transform "translate(-50%, -50%)"}}
         (tr "workspace.libraries.colors.empty-typography-palette")]
        [:div
         {:class (stl/css :text-palette-inside)
          :style {:position "relative"
                  :max-width (str width "px")
                  :right (str (* offset-step offset) "px")}}
         (for [[idx item] (map-indexed vector current-typographies)]
           [:& typography-item
            {:key idx
             :file-id file-id
             :current-file-id current-file-id
             :selected-ids selected-ids
             :typography item
             :size size}])])]

     (when show-arrows?
       [:button {:class (stl/css :right-arrow)
                 :disabled (= offset max-offset)
                 :on-click on-right-arrow-click} i/arrow])]))

(mf/defc text-palette
  {::mf/wrap [mf/memo]}
  [{:keys [size width selected] :as props}]
  (let [selected-ids      (mf/deref refs/selected-shapes)

        ;; FIXME: we have duplicate operations, if we already have the
        ;; libraries, so we already have file-typographies so we don't
        ;; need two separate lens/refs for that
        file-typographies (mf/deref refs/workspace-file-typography)
        libraries         (mf/deref refs/files)
        current-file-id   (mf/use-ctx ctx/current-file-id)]
    [:& palette {:current-file-id current-file-id
                 :selected-ids selected-ids
                 :file-typographies file-typographies
                 :libraries libraries
                 :width width
                 :selected selected
                 :size size}]))
