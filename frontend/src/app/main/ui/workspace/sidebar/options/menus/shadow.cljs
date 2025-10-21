;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.shadow
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as clr]
   [app.common.types.shape.shadow :as ctss]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.sidebar.options.rows.shadow-row :refer [shadow-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def shadow-attrs [:shadow])

(defn- create-shadow
  []
  {:id (uuid/next)
   :style :drop-shadow
   :color {:color clr/black
           :opacity 0.2}
   :offset-x 4
   :offset-y 4
   :blur 4
   :spread 0
   :hidden false})

(defn- remove-shadow-by-index
  [values index]
  (->> (d/enumerate values)
       (filterv (fn [[idx _]] (not= idx index)))
       (mapv second)))

(def ^:private xf:add-index
  (map-indexed (fn [index shadow]
                 (assoc shadow ::index index))))

(mf/defc shadow-menu*
  [{:keys [ids type values] :as props}]
  (let [shadows        (mf/with-memo [values]
                         (if (= :multiple values)
                           values
                           (not-empty (into [] xf:add-index values))))

        ids-ref        (h/use-update-ref ids)

        open-state*    (mf/use-state {})
        open-state     (deref open-state*)

        has-shadows?   (or (= :multiple shadows)
                           (some? (seq shadows)))

        show-content*  (mf/use-state true)
        show-content?  (deref show-content*)

        toggle-content
        (mf/use-fn #(swap! show-content* not))

        on-toggle-open
        (mf/use-fn #(swap! open-state* update % not))

        on-remove-all
        (mf/use-fn
         (fn []
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dw/trigger-bounding-box-cloaking ids))
             (st/emit! (dwsh/update-shapes ids #(dissoc % :shadow))))))

        handle-reorder
        (mf/use-fn
         (fn [from-pos to-space-between-pos]
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dw/trigger-bounding-box-cloaking ids))
             (st/emit! (dc/reorder-shadows ids from-pos to-space-between-pos)))))

        on-add-shadow
        (mf/use-fn
         (fn []
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dw/trigger-bounding-box-cloaking ids))
             (st/emit! (dc/add-shadow ids (create-shadow))))))

        on-detach-color
        (mf/use-fn
         (fn [index]
           (let [ids (mf/ref-val ids-ref)
                 f   #(update-in % [:shadow index :color] dissoc :id :file-id :ref-id :ref-file)]
             (st/emit! (dw/trigger-bounding-box-cloaking ids))
             (st/emit! (dwsh/update-shapes ids f)))))

        on-toggle-visibility
        (mf/use-fn
         (fn [index]
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dw/trigger-bounding-box-cloaking ids))
             (st/emit! (dwsh/update-shapes ids #(update-in % [:shadow index :hidden] not))))))

        on-remove
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dw/trigger-bounding-box-cloaking ids))
             (st/emit! (dwsh/update-shapes ids #(update % :shadow remove-shadow-by-index index))))))

        on-update
        (mf/use-fn
         (fn [index attr value]
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dwsh/update-shapes ids
                                           (fn [shape]
                                             (update-in shape [:shadow index]
                                                        (fn [shadow]
                                                          (-> shadow
                                                              (assoc attr value)
                                                              (ctss/check-shadow))))))))))]
    [:div {:class (stl/css :shadow-section)}
     [:div {:class (stl/css :shadow-title)}
      [:> title-bar* {:collapsable  has-shadows?
                      :collapsed    (not show-content?)
                      :on-collapsed toggle-content
                      :title        (case type
                                      :multiple (tr "workspace.options.shadow-options.title.multiple")
                                      :group (tr "workspace.options.shadow-options.title.group")
                                      (tr "workspace.options.shadow-options.title"))
                      :class        (stl/css-case :shadow-title-bar (not has-shadows?))}

       (when-not (= :multiple shadows)
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.shadow-options.add-shadow")
                           :on-click on-add-shadow
                           :icon i/add
                           :data-testid "add-shadow"}])]]

     (when show-content?
       (cond
         (= :multiple shadows)
         [:div {:class (stl/css :shadow-content)}
          [:div {:class (stl/css :shadow-multiple)}
           [:div {:class (stl/css :shadow-multiple-label)}
            (tr "settings.multiple")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.shadow-options.remove-shadow")
                             :on-click on-remove-all
                             :icon i/remove}]]]

         (some? shadows)
         [:> h/sortable-container* {}
          [:div {:class (stl/css :shadow-content)}
           (for [{:keys [::index id] :as shadow} shadows]
             [:> shadow-row*
              {:key (dm/str index)
               :index index
               :shadow shadow
               :on-update on-update
               :on-remove on-remove
               :on-toggle-visibility on-toggle-visibility
               :on-detach-color on-detach-color
               :is-open (get open-state id)
               :on-reorder handle-reorder
               :on-toggle-open on-toggle-open}])]]))]))
