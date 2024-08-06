;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.color-selection
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as ctc]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.selection :as dws]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- prepare-colors
  [shapes file-id shared-libs]
  (let [data           (into [] (remove nil? (ctc/extract-all-colors shapes file-id shared-libs)))
        grouped-colors (group-by :attrs data)
        all-colors     (distinct (mapv :attrs data))

        tmp            (group-by #(some? (:id %)) all-colors)
        library-colors (get tmp true)
        colors         (get tmp false)]
    {:grouped-colors grouped-colors
     :all-colors all-colors
     :colors colors
     :library-colors library-colors}))

(mf/defc color-selection-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shapes"]))]
   ::mf/wrap-props false}
  [{:keys [shapes file-id shared-libs]}]
  (let [{:keys [grouped-colors library-colors colors]} (mf/with-memo [shapes file-id shared-libs]
                                                         (prepare-colors shapes file-id shared-libs))

        state*          (mf/use-state true)
        open?           (deref state*)

        has-colors?     (or (some? (seq colors)) (some? (seq library-colors)))

        toggle-content  (mf/use-fn #(swap! state* not))

        expand-lib-color (mf/use-state false)
        expand-color     (mf/use-state false)

        grouped-colors*  (mf/use-var nil)
        prev-colors*     (mf/use-var [])

        on-change
        (mf/use-fn
         (fn [new-color old-color from-picker?]
           (let [old-color       (-> old-color (dissoc :name :path) d/without-nils)

                 ;; When dragging on the color picker sometimes all
                 ;; the shapes hasn't updated the color to the prev
                 ;; value so we need this extra calculation
                 shapes-by-old-color  (get @grouped-colors* old-color)
                 prev-color           (d/seek #(get @grouped-colors* %) @prev-colors*)
                 shapes-by-prev-color (get @grouped-colors* prev-color)
                 shapes-by-color (or shapes-by-prev-color shapes-by-old-color)]

             (when from-picker?
               (swap! prev-colors* conj (-> new-color (dissoc :name :path) d/without-nils)))

             (st/emit! (dc/change-color-in-selected new-color shapes-by-color (or prev-color old-color))))))

        on-open
        (mf/use-fn
         (fn []
           (reset! prev-colors* [])))

        on-close
        (mf/use-fn
         (fn []
           (reset! prev-colors* [])))

        on-detach
        (mf/use-fn
         (fn [color]
           (let [shapes-by-color (get @grouped-colors* color)
                 new-color       (assoc color :id nil :file-id nil)]
             (st/emit! (dc/change-color-in-selected new-color shapes-by-color color)))))

        select-only
        (mf/use-fn
         (fn [color]
           (let [shapes-by-color (get @grouped-colors* color)
                 ids (into (d/ordered-set) (map :shape-id) shapes-by-color)]
             (st/emit! (dws/select-shapes ids)))))]

    (mf/with-effect [grouped-colors]
      (reset! grouped-colors* grouped-colors))

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:& title-bar {:collapsable  has-colors?
                     :collapsed    (not open?)
                     :on-collapsed toggle-content
                     :title        (tr "workspace.options.selection-color")
                     :class        (stl/css-case :title-spacing-selected-colors (not has-colors?))}]]

     (when open?
       [:div {:class (stl/css :element-content)}
        [:div {:class (stl/css :selected-color-group)}
         (for [[index color] (d/enumerate (take 3 library-colors))]
           [:& color-row {:key (dm/str "library-color-" index)
                          :color color
                          :index index
                          :on-detach on-detach
                          :select-only select-only
                          :on-change #(on-change %1 color %2)
                          :on-open on-open
                          :on-close on-close}])
         (when (and (false? @expand-lib-color) (< 3 (count library-colors)))
           [:button  {:class (stl/css :more-colors-btn)
                      :on-click #(reset! expand-lib-color true)}
            (tr "workspace.options.more-lib-colors")])
         (when @expand-lib-color
           (for [[index color] (d/enumerate (drop 3 library-colors))]
             [:& color-row {:key (dm/str "library-color-" index)
                            :color color
                            :index index
                            :on-detach on-detach
                            :select-only select-only
                            :on-change #(on-change %1 color %2)
                            :on-open on-open
                            :on-close on-close}]))]
        [:div {:class (stl/css :selected-color-group)}
         (for [[index color] (d/enumerate (take 3 colors))]
           [:& color-row {:key (dm/str "color-" index)
                          :color color
                          :index index
                          :select-only select-only
                          :on-change #(on-change %1 color %2)
                          :on-open on-open
                          :on-close on-close}])
         (when (and (false? @expand-color) (< 3 (count colors)))
           [:button  {:class (stl/css :more-colors-btn)
                      :on-click #(reset! expand-color true)}
            (tr "workspace.options.more-colors")])

         (when @expand-color
           (for [[index color] (d/enumerate (drop 3 colors))]
             [:& color-row {:key (dm/str "color-" (:color color))
                            :color color
                            :index index
                            :select-only select-only
                            :on-change #(on-change %1 color %2)
                            :on-open on-open
                            :on-close on-close}]))]])]))
