;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.colorpalette
  (:require
   [app.common.math :as mth]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; --- Refs

(def palettes-ref
  (-> (l/in [:library :palettes])
      (l/derived st/state)))

(def selected-palette-ref
  (-> (l/in [:workspace-local :selected-palette])
      (l/derived st/state)))

(def selected-palette-size-ref
  (-> (l/in [:workspace-local :selected-palette-size])
      (l/derived st/state)))

;; --- Components
(mf/defc palette-item
  [{:keys [color]}]
  (let [ids-with-children (map :id (mf/deref refs/selected-shapes-with-children))
        select-color
        (fn [event]
          (if (kbd/alt? event)
              (st/emit! (mdc/change-stroke ids-with-children (merge uc/empty-color color) 0))
              (st/emit! (mdc/change-fill ids-with-children (merge uc/empty-color color) 0))))]

    [:div.color-cell {:on-click select-color}
     [:& cb/color-bullet {:color color}]
     [:& cb/color-name {:color color}]]))

(mf/defc palette
  [{:keys [current-colors recent-colors file-colors shared-libs selected]}]
  (let [state      (mf/use-state {:show-menu false})

        width      (:width @state 0)
        visible    (mth/round (/ width 66))

        offset     (:offset @state 0)
        max-offset (- (count current-colors)
                      visible)

        container  (mf/use-ref nil)

        {:keys [on-pointer-down on-lost-pointer-capture on-mouse-move parent-ref size]}
        (use-resize-hook :palette 72 54 80 :y true :bottom)

        on-left-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [_]
           (swap! state update :offset
                  (fn [offset]
                    (if (pos? offset)
                      (max (- offset (mth/round (/ visible 2))) 0)
                      offset)))))

        on-right-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [_]
           (swap! state update :offset
                  (fn [offset]
                    (if (< offset max-offset)
                      (min max-offset (+ offset (mth/round (/ visible 2))))
                      offset)))))

        on-scroll
        (mf/use-callback
         (mf/deps max-offset)
         (fn [event]
           (let [delta (+ (.. event -nativeEvent -deltaY) (.. event -nativeEvent -deltaX))]
             (if (pos? delta)
               (on-right-arrow-click event)
               (on-left-arrow-click event)))))

        on-resize
        (mf/use-callback
         (fn [_]
           (let [dom   (mf/ref-val container)
                 width (obj/get dom "clientWidth")]
             (swap! state assoc :width width))))]


    (mf/use-layout-effect
     #(let [dom   (mf/ref-val container)
            width (obj/get dom "clientWidth")]
        (swap! state assoc :width width)))

    (mf/use-effect
     #(let [key1 (events/listen js/window "resize" on-resize)]
        (fn []
          (events/unlistenByKey key1))))

    [:div.color-palette {:ref parent-ref
                         :class (dom/classnames :no-text (< size 72))
                         :style #js {"--height" (str size "px")
                                     "--bullet-size" (str (if (< size 72) (- size 15) (- size 30)) "px")}}
     [:div.resize-area {:on-pointer-down on-pointer-down
                        :on-lost-pointer-capture on-lost-pointer-capture
                        :on-mouse-move on-mouse-move}]
     [:& dropdown {:show (:show-menu @state)
                   :on-close #(swap! state assoc :show-menu false)}
      [:ul.workspace-context-menu.palette-menu
       (for [[idx cur-library] (map-indexed vector (vals shared-libs))]
         (let [colors (-> cur-library (get-in [:data :colors]) vals)]
           [:li.palette-library
            {:key (str "library-" idx)
             :on-click #(st/emit! (mdc/change-palette-selected (:id cur-library)))}
            (when (= selected (:id cur-library)) i/tick)
            [:div.library-name (str (:name cur-library) " " (str/format "(%s)" (count colors)))]
            [:div.color-sample
             (for [[idx {:keys [color]}] (map-indexed vector (take 7 colors))]
               [:& cb/color-bullet {:key (str "color-" idx)
                                    :color color}])]]))


       [:li.palette-library
        {:on-click #(st/emit! (mdc/change-palette-selected :file))}
        (when (= selected :file) i/tick)
        [:div.library-name (str (tr "workspace.libraries.colors.file-library")
                                (str/format " (%s)" (count file-colors)))]
        [:div.color-sample
         (for [[idx color] (map-indexed vector (take 7 (vals file-colors))) ]
           [:& cb/color-bullet {:key (str "color-" idx)
                                :color color}])]]

       [:li.palette-library
        {:on-click #(st/emit! (mdc/change-palette-selected :recent))}
        (when (= selected :recent) i/tick)
        [:div.library-name (str (tr "workspace.libraries.colors.recent-colors")
                                (str/format " (%s)" (count recent-colors)))]
        [:div.color-sample
         (for [[idx color] (map-indexed vector (take 7 (reverse recent-colors))) ]
           [:& cb/color-bullet {:key (str "color-" idx)
                                :color color}])]]]]

     [:div.color-palette-actions
      {:on-click #(swap! state assoc :show-menu true)}
      [:div.color-palette-actions-button i/actions]]

     [:span.left-arrow {:on-click on-left-arrow-click} i/arrow-slide]
     [:div.color-palette-content {:ref container :on-wheel on-scroll}
      [:div.color-palette-inside {:style {:position "relative"
                                          :right (str (* 66 offset) "px")}}
       (for [[idx item] (map-indexed vector current-colors)]
         [:& palette-item {:color item :key idx}])]]

     [:span.right-arrow {:on-click on-right-arrow-click} i/arrow-slide]]))

(defn library->colors [shared-libs selected]
  (map #(merge % {:file-id selected})
       (-> shared-libs
           (get-in [selected :data :colors])
           (vals))))

(mf/defc colorpalette
  {::mf/wrap [mf/memo]}
  []
  (let [recent-colors (mf/deref refs/workspace-recent-colors)
        file-colors   (mf/deref refs/workspace-file-colors)
        shared-libs   (mf/deref refs/workspace-libraries)
        selected      (or (mf/deref selected-palette-ref) :recent)
        current-library-colors (mf/use-state [])]

    (mf/use-effect
     (mf/deps selected)
     (fn []
       (reset! current-library-colors
               (into []
                     (cond
                       (= selected :recent) (reverse recent-colors)
                       (= selected :file)   (->> (vals file-colors) (sort-by :name))
                       :else                (->> (library->colors shared-libs selected) (sort-by :name)))))))

    (mf/use-effect
     (mf/deps recent-colors)
     (fn []
       (when (= selected :recent)
         (reset! current-library-colors (reverse recent-colors)))))

    (mf/use-effect
     (mf/deps file-colors)
     (fn []
       (when (= selected :file)
         (reset! current-library-colors (into [] (->> (vals file-colors)
                                                      (sort-by :name)))))))

    [:& palette {:current-colors @current-library-colors
                 :recent-colors recent-colors
                 :file-colors file-colors
                 :shared-libs shared-libs
                 :selected selected}]))
