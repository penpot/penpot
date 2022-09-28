;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.textpalette
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as f]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc typography-item
  [{:keys [file-id selected-ids typography name-only?]}]
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

    [:div.typography-item {:on-click handle-click}
     [:div.typography-name
      {:style {:font-family (:font-family typography)
               :font-weight (:font-weight typography)
               :font-style (:font-style typography)}}
      (:name typography)]
     (when-not name-only?
       [:*
        [:div.typography-font (:name font-data)]
        [:div.typography-data (str (:font-size typography) "px | " (:name variant-data))]])]))

(mf/defc palette
  [{:keys [selected-ids current-file-id file-typographies shared-libs]}]

  (let [state (mf/use-state {:show-menu false})
        selected (mf/use-state :file)

        file-id
        (case @selected
          :recent nil
          :file current-file-id
          @selected)

        current-typographies
        (case @selected
          :recent []
          :file (vals file-typographies)
          (vals (get-in shared-libs [@selected :data :typographies])))

        container (mf/use-ref nil)

        on-left-arrow-click
        (mf/use-callback
         (fn []
           (when-let [node (mf/ref-val container)]
             (.scrollBy node #js {:left -200 :behavior "smooth"}))))

        on-right-arrow-click
        (mf/use-callback
         (fn []
           (when-let [node (mf/ref-val container)]
             (.scrollBy node #js {:left 200 :behavior "smooth"}))))

        on-wheel
        (mf/use-callback
         (fn [event]
           (let [delta (+ (.. event -nativeEvent -deltaY) (.. event -nativeEvent -deltaX))]
             (if (pos? delta)
               (on-right-arrow-click)
               (on-left-arrow-click)))))

        {:keys [on-pointer-down on-lost-pointer-capture on-mouse-move parent-ref size]}
        (use-resize-hook :palette 72 54 80 :y true :bottom)]

    [:div.color-palette {:ref parent-ref
                         :class (dom/classnames :no-text (< size 72))
                         :style #js {"--height" (str size "px")}}
     [:div.resize-area {:on-pointer-down on-pointer-down
                        :on-lost-pointer-capture on-lost-pointer-capture
                        :on-mouse-move on-mouse-move}]
     [:& dropdown {:show (:show-menu @state)
                   :on-close #(swap! state assoc :show-menu false)}

      [:ul.workspace-context-menu.palette-menu
       (for [[idx cur-library] (map-indexed vector (vals shared-libs))]
         (let [typographies (-> cur-library (get-in [:data :typographies]) vals)]
           [:li.palette-library
            {:key (str "library-" idx)
             :on-click #(reset! selected (:id cur-library))}

            (when (= @selected (:id cur-library)) i/tick)

            [:div.library-name (str (:name cur-library) " " (str/format "(%s)" (count typographies)))]]))

       [:li.palette-library
        {:on-click #(reset! selected :file)}
        (when (= selected :file) i/tick)
        [:div.library-name (str (tr "workspace.libraries.colors.file-library")
                                (str/format " (%s)" (count file-typographies)))]]]]

     [:div.color-palette-actions
      {:on-click #(swap! state assoc :show-menu true)}
      [:div.color-palette-actions-button i/actions]]

     [:span.left-arrow {:on-click on-left-arrow-click} i/arrow-slide]

     [:div.color-palette-content {:ref container :on-wheel on-wheel}
      [:div.color-palette-inside
       (for [[idx item] (map-indexed vector current-typographies)]
         [:& typography-item
          {:key idx
           :file-id file-id
           :selected-ids selected-ids
           :typography item}])]]

     [:span.right-arrow {:on-click on-right-arrow-click} i/arrow-slide]]))

(mf/defc textpalette
  {::mf/wrap [mf/memo]}
  []
  (let [selected-ids      (mf/deref refs/selected-shapes)
        file-typographies (mf/deref refs/workspace-file-typography)
        shared-libs       (mf/deref refs/workspace-libraries)
        current-file-id   (mf/use-ctx ctx/current-file-id)]
    [:& palette {:current-file-id current-file-id
                 :selected-ids selected-ids
                 :file-typographies file-typographies
                 :shared-libs shared-libs}]))
