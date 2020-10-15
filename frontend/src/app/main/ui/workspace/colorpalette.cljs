;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpalette
  (:require
   [beicon.core :as rx]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.common.math :as mth]
   [app.main.data.colors :as mdc]
   [app.main.data.workspace :as udw]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.util.color :refer [hex->rgb]]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.main.refs :as refs]
   [app.util.i18n :as i18n :refer [t]]))

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

(defn- make-selected-palette-item-ref
  [lib-id]
  (-> (l/in [:library-items :palettes lib-id])
      (l/derived st/state)))

;; --- Components
(mf/defc palette-item
  [{:keys [color size local?]}]
  (let [id (:id color)
        file-id (:file-id color)
        select-color
        (fn [event]
          (let [ids (get-in @st/state [:workspace-local :selected])]
            (if (kbd/shift? event)
              (st/emit! (mdc/change-stroke ids color))
              (st/emit! (mdc/change-fill ids color)))))]

    [:div.color-cell {:class (str "cell-"(name size))
                      :on-click select-color}
     [:& cb/color-bullet {:color color}]
     [:& cb/color-name {:color color :size size}]]))

(mf/defc palette
  [{:keys [left-sidebar? current-colors recent-colors file-colors shared-libs selected size]}]
  (let [state      (mf/use-state {:show-menu false })

        width      (:width @state 0)
        visible    (mth/round (/ width 66))

        offset     (:offset @state 0)
        max-offset (- (count current-colors)
                      visible)

        close-fn   #(st/emit! (udw/toggle-layout-flags :colorpalette))
        container  (mf/use-ref nil)

        locale    (mf/deref i18n/locale)

        on-left-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [event]
           (swap! state update :offset
                  (fn [offset]
                    (if (pos? offset)
                      (max (- offset (mth/round (/ visible 2))) 0)
                      offset)))))

        on-right-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [event]
           (swap! state update :offset
                  (fn [offset]
                    (if (< offset max-offset)
                      (min max-offset (+ offset (mth/round (/ visible 2))))
                      offset)))))

        on-scroll
        (mf/use-callback
         (mf/deps max-offset)
         (fn [event]
           (if (pos? (.. event -nativeEvent -deltaY))
             (on-right-arrow-click event)
             (on-left-arrow-click event))))

        on-resize
        (mf/use-callback
         (fn [event]
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

    [:div.color-palette {:class (when left-sidebar? "left-sidebar-open")}
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
             (for [[idx {:keys [id color]}] (map-indexed vector (take 7 colors))]
               [:& cb/color-bullet {:key (str "color-" idx)
                                    :color color}])]]))


       [:li.palette-library
        {:on-click #(st/emit! (mdc/change-palette-selected :file))}
        (when (= selected :file) i/tick)
        [:div.library-name (str (t locale "workspace.libraries.colors.file-library")
                                (str/format " (%s)" (count file-colors)))]
        [:div.color-sample
         (for [[idx color] (map-indexed vector (take 7 (vals file-colors))) ]
           [:& cb/color-bullet {:key (str "color-" idx)
                                :color color}])]]

       [:li.palette-library
        {:on-click #(st/emit! (mdc/change-palette-selected :recent))}
        (when (= selected :recent) i/tick)
        [:div.library-name (str (t locale "workspace.libraries.colors.recent-colors")
                                (str/format " (%s)" (count recent-colors)))]
        [:div.color-sample
         (for [[idx color] (map-indexed vector (take 7 (reverse recent-colors))) ]
           [:& cb/color-bullet {:key (str "color-" idx)
                                :color color}])]]

       [:hr.dropdown-separator]

       [:li
        {:on-click #(st/emit! (mdc/change-palette-size :big))}
        (when (= size :big) i/tick)
        (t locale "workspace.libraries.colors.big-thumbnails")]

       [:li
        {:on-click #(st/emit! (mdc/change-palette-size :small))}
        (when (= size :small) i/tick)
        (t locale "workspace.libraries.colors.small-thumbnails")]]]

     [:div.color-palette-actions
      {:on-click #(swap! state assoc :show-menu true)}
      [:div.color-palette-actions-button i/actions]]

     [:span.left-arrow {:on-click on-left-arrow-click} i/arrow-slide]
     [:div.color-palette-content {:class (if (= size :big) "size-big" "size-small")
                                  :ref container :on-wheel on-scroll}
      [:div.color-palette-inside {:style {:position "relative"
                                          :right (str (* 66 offset) "px")}}
       (for [[idx item] (map-indexed vector current-colors)]
         [:& palette-item {:size size
                           :color item
                           :key idx}])]]

     [:span.right-arrow {:on-click on-right-arrow-click} i/arrow-slide]]))

(defn library->colors [shared-libs selected]
  (map #(merge {:file-id selected} %)
       (vals (get-in shared-libs [selected :data :colors]))))

(mf/defc colorpalette
  [{:keys [left-sidebar? team-id]}]
  (let [recent-colors (mf/deref refs/workspace-recent-colors)
        file-colors   (mf/deref refs/workspace-file-colors)
        shared-libs   (mf/deref refs/workspace-libraries)
        selected      (or (mf/deref selected-palette-ref) :recent)
        size      (or (mf/deref selected-palette-size-ref) :big)

        current-library-colors (mf/use-state [])]

    (mf/use-effect
     (mf/deps selected)
     (fn []
       (reset! current-library-colors
               (into []
                     (cond
                       (= selected :recent) (reverse recent-colors)
                       (= selected :file)   (vals file-colors)
                       :else                (library->colors shared-libs selected))))))

    (mf/use-effect
     (mf/deps recent-colors)
     (fn []
       (when (= selected :recent)
         (reset! current-library-colors (reverse recent-colors)))))

    (mf/use-effect
     (mf/deps file-colors)
     (fn []
       (when (= selected :file)
         (reset! current-library-colors (into [] (vals file-colors))))))

    [:& palette {:left-sidebar? left-sidebar?
                 :current-colors @current-library-colors
                 :recent-colors recent-colors
                 :file-colors file-colors
                 :shared-libs shared-libs
                 :selected selected
                 :size size}]))
