;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.colorpalette
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; --- Components

(mf/defc palette-item
  {::mf/wrap [mf/memo]}
  [{:keys [color]}]
  (letfn [(select-color [event]
            (st/emit! (mdc/apply-color-from-palette color (kbd/alt? event))))]
    [:div.color-cell {:on-click select-color}
     [:& cb/color-bullet {:color color}]
     [:& cb/color-name {:color color}]]))

(mf/defc palette
  [{:keys [current-colors recent-colors file-colors shared-libs selected on-select]}]
  (let [state      (mf/use-state {:show-menu false})

        width      (:width @state 0)
        visible    (/ width 66)

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
                      (max (- offset (/ visible 2)) 0)
                      offset)))))

        on-right-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [_]
           (swap! state update :offset
                  (fn [offset]
                    (if (< offset max-offset)
                      (min max-offset (+ offset (/ visible 2)))
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
             (swap! state assoc :width width))))

        on-select-palette
        (mf/use-fn
         (mf/deps on-select)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 value (dom/get-attribute node "data-palette")]
             (on-select (if (or (= "file" value) (= "recent" value))
                          (keyword value)
                          (parse-uuid value))))))]

    (mf/use-layout-effect
     #(let [dom   (mf/ref-val container)
            width (obj/get dom "clientWidth")]
        (swap! state assoc :width width)))

    (mf/with-effect []
      (let [key1 (events/listen js/window "resize" on-resize)]
        #(events/unlistenByKey key1)))

    [:div.color-palette {:ref parent-ref
                         :class (dom/classnames :no-text (< size 72))
                         :style #js {"--height" (dm/str size "px")
                                     "--bullet-size" (dm/str (if (< size 72) (- size 15) (- size 30)) "px")}}
     [:div.resize-area {:on-pointer-down on-pointer-down
                        :on-lost-pointer-capture on-lost-pointer-capture
                        :on-mouse-move on-mouse-move}]
     [:& dropdown {:show (:show-menu @state)
                   :on-close #(swap! state assoc :show-menu false)}
      [:ul.workspace-context-menu.palette-menu
       (for [{:keys [data id] :as library} (vals shared-libs)]
         (let [colors (-> data :colors vals)]
           [:li.palette-library
            {:key (dm/str "library-" id)
             :on-click on-select-palette
             :data-palette (dm/str id)}
            (when (= selected id) i/tick)
            [:div.library-name (str (:name library) " " (str/ffmt "(%)" (count colors)))]
            [:div.color-sample
             (for [[i {:keys [color]}] (map-indexed vector (take 7 colors))]
               [:& cb/color-bullet {:key (dm/str "color-" i)
                                    :color color}])]]))


       [:li.palette-library
        {:on-click on-select-palette
         :data-palette "file"}
        (when (= selected :file) i/tick)
        [:div.library-name (dm/str
                            (tr "workspace.libraries.colors.file-library")
                            (str/ffmt " (%)" (count file-colors)))]
        [:div.color-sample
         (for [[i color] (map-indexed vector (take 7 (vals file-colors))) ]
           [:& cb/color-bullet {:key (dm/str "color-" i)
                                :color color}])]]

       [:li.palette-library
        {:on-click on-select-palette
         :data-palette "recent"}
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
        selected      (h/use-persistent-state ::selected :recent)

        colors        (mf/use-state [])
        on-select     (mf/use-fn #(reset! selected %))]

    (mf/with-effect [@selected]
      (fn []
        (reset! colors
                (into []
                      (cond
                        (= @selected :recent) (reverse recent-colors)
                        (= @selected :file)   (->> (vals file-colors) (sort-by :name))
                        :else                 (->> (library->colors shared-libs @selected) (sort-by :name)))))))

    (mf/with-effect [recent-colors @selected]
      (when (= @selected :recent)
        (reset! colors (reverse recent-colors))))

    (mf/with-effect [file-colors @selected]
      (when (= @selected :file)
        (reset! colors (into [] (->> (vals file-colors)
                                     (sort-by :name))))))

    [:& palette {:current-colors @colors
                 :recent-colors recent-colors
                 :file-colors file-colors
                 :shared-libs shared-libs
                 :selected @selected
                 :on-select on-select}]))
