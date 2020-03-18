;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.dashboard.colors
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.colors :as dc]
   [uxbox.main.store :as st]
   [uxbox.main.ui.dashboard.common :as common]
   [uxbox.main.ui.colorpicker :refer [colorpicker]]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.keyboard :as k]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.color :refer [hex->rgb]]
   [uxbox.util.data :refer [seek]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as t :refer [tr]]
   [uxbox.util.router :as rt]))

;; ;; --- Refs
;; 
;; (def collections-iref
;;   (-> (l/key :colors-collections)
;;       (l/derive st/state)))
;; 
;; (def selected-colors-iref
;;   (-> (l/in [:dashboard :colors :selected])
;;       (l/derive st/state)))
;; 
;; ;; --- Colors Modal (Component)
;; 
;; (mf/defc color-modal
;;   [{:keys [on-submit value] :as props}]
;;   (let [local (mf/use-var value)]
;;     [:div.lightbox-body
;;      [:h3 (tr "ds.color-lightbox.title" )]
;;      [:form
;;       [:div.row-flex.center
;;        [:& colorpicker {:value (or @local "#00ccff")
;;                         :on-change #(reset! local %)}]]
;;       [:input#project-btn.btn-primary
;;        {:value (tr "ds.color-lightbox.add")
;;         :on-click #(on-submit @local)
;;         :type "button"}]]]))
;; 
;; ;; --- Page Title
;; 
;; 
;; (mf/defc grid-header
;;   [{:keys [coll] :as props}]
;;   (letfn [(on-change [name]
;;             (st/emit! (dc/rename-collection (:id coll) name)))
;; 
;;           (delete []
;;             (st/emit!
;;              (dc/delete-collection (:id coll))
;;              (rt/nav :dashboard-colors nil {:type (:type coll)})))
;; 
;;           (on-delete []
;;             (modal/show! confirm-dialog {:on-accept delete}))]
;;     [:& common/grid-header {:value (:name coll)
;;                             :on-change on-change
;;                             :on-delete on-delete}]))
;; 
;; ;; --- Nav
;; 
;; (mf/defc nav-item
;;   [{:keys [coll selected?] :as props}]
;;   (let [local (mf/use-state {})
;;         {:keys [id type name]} coll
;;         colors (count (:colors coll))
;;         editable? (= type :own)]
;;     (letfn [(on-click [event]
;;               (let [type (or type :own)]
;;                 (st/emit! (rt/nav :dashboard-colors nil {:type type :id id}))))
;;             (on-input-change [event]
;;                 (let [value (dom/get-target event)
;;                       value (dom/get-value value)]
;;                   (swap! local assoc :name value)))
;;             (on-cancel [event]
;;               (swap! local dissoc :name)
;;               (swap! local dissoc :edit))
;;             (on-double-click [event]
;;               (when editable?
;;                 (swap! local assoc :edit true)))
;;             (on-input-keyup [event]
;;               (when (k/enter? event)
;;                 (let [value (dom/get-target event)
;;                       value (dom/get-value value)]
;;                   (st/emit! (dc/rename-collection id (str/trim (:name @local))))
;;                   (swap! local assoc :edit false))))]
;;       [:li {:on-click on-click
;;             :on-double-click on-double-click
;;             :class-name (when selected? "current")}
;;        (if (:edit @local)
;;          [:div
;;           [:input.element-title
;;            {:value (if (:name @local) (:name @local) name)
;;             :on-change on-input-change
;;             :on-key-down on-input-keyup}]
;;           [:span.close {:on-click on-cancel} i/close]]
;;          [:span.element-title name])
;;        #_[:span.element-subtitle
;;         (tr "ds.num-elements" (t/c colors))]])))
;; 
;; (mf/defc nav
;;   [{:keys [id type colls selected-coll] :as props}]
;;   (let [own? (= type :own)
;;         builtin? (= type :builtin)
;;         select-tab #(st/emit! (rt/nav :dashboard-colors nil {:type %}))]
;;     [:div.library-bar
;;      [:div.library-bar-inside
;;       [:ul.library-tabs
;;        [:li {:class-name (when own? "current")
;;              :on-click (partial select-tab :own)}
;;         (tr "ds.your-colors-title")]
;;        [:li {:class-name (when builtin? "current")
;;              :on-click (partial select-tab :builtin)}
;;         (tr "ds.store-colors-title")]]
;;       [:ul.library-elements
;;        (when own?
;;          [:li
;;           [:a.btn-primary {:on-click #(st/emit! (dc/create-collection))}
;;            (tr "ds.colors-collection.new")]])
;;        (for [item colls]
;;          (let [selected? (= (:id item) (:id selected-coll))]
;;            [:& nav-item {:coll item :selected? selected? :key (:id item)}]))]]]))
;; 
;; ;; --- Grid
;; 
;; (mf/defc grid-form
;;   [{:keys [id] :as props}]
;;   (letfn [(on-submit [val]
;;             (st/emit! (dc/add-color id val))
;;             (modal/hide!))
;;           (on-click [event]
;;             (modal/show! color-modal {:on-submit on-submit}))]
;;     [:div.grid-item.small-item.add-project {:on-click on-click}
;;      [:span (tr "ds.color-new")]]))
;; 
;; (mf/defc grid-options-tooltip
;;   [{:keys [selected on-select title] :as props}]
;;   {:pre [(uuid? selected)
;;          (fn? on-select)
;;          (string? title)]}
;;   (let [colls (mf/deref collections-iref)
;;         colls (->> (vals colls)
;;                    (filter #(= :own (:type %)))
;;                    (remove #(= selected (:id %)))
;;                    (sort-by :name colls))
;;         on-select (fn [event id]
;;                     (dom/prevent-default event)
;;                     (dom/stop-propagation event)
;;                     (on-select id))]
;;     [:ul.move-list
;;      [:li.title title]
;;      (for [{:keys [id name] :as coll} colls]
;;        [:li {:key (str id)}
;;         [:a {:on-click #(on-select % id)} name]])]))
;; 
;; (mf/defc grid-options
;;   [{:keys [id type coll selected] :as props}]
;;   (let [local (mf/use-state {})]
;;     (letfn [(delete [event]
;;               (st/emit! (dc/delete-colors id selected)))
;;             (on-delete [event]
;;               (modal/show! confirm-dialog {:on-accept delete}))
;;             (on-toggle-copy [event]
;;               (swap! local update :show-copy-tooltip not)
;;               (swap! local assoc :show-move-tooltip false))
;;             (on-toggle-move [event]
;;               (swap! local update :show-move-tooltip not)
;;               (swap! local assoc :show-copy-tooltip false))
;;             (on-copy [selected]
;;               (swap! local assoc
;;                      :show-move-tooltip false
;;                      :show-copy-tooltip false)
;;               (st/emit! (dc/copy-selected selected)))
;;             (on-move [selected]
;;               (swap! local assoc
;;                      :show-move-tooltip false
;;                      :show-copy-tooltip false)
;;               (st/emit! (dc/move-selected id selected)))]
;; 
;;       ;; MULTISELECT OPTIONS BAR
;;       [:div.multiselect-bar
;;        (if (or (= type :own) (nil? id))
;;          ;; if editable
;;          [:div.multiselect-nav
;;           [:span.move-item.tooltip.tooltip-top
;;            {:alt (tr "ds.multiselect-bar.copy")
;;             :on-click on-toggle-copy}
;;            (when (:show-copy-tooltip @local)
;;              [:& grid-options-tooltip {:selected id
;;                                        :title (tr "ds.multiselect-bar.copy-to-library")
;;                                        :on-select on-copy}])
;;            i/copy]
;;           [:span.move-item.tooltip.tooltip-top
;;            {:alt (tr "ds.multiselect-bar.move")
;;             :on-click on-toggle-move}
;;            (when (:show-move-tooltip @local)
;;              [:& grid-options-tooltip {:selected id
;;                                        :title (tr "ds.multiselect-bar.move-to-library")
;;                                        :on-select on-move}])
;;            i/move]
;;           [:span.delete.tooltip.tooltip-top
;;            {:alt (tr "ds.multiselect-bar.delete")
;;             :on-click on-delete}
;;            i/trash]]
;; 
;;          ;; if not editable
;;          [:div.multiselect-nav
;;           [:span.move-item.tooltip.tooltip-top
;;            {:alt (tr "ds.multiselect-bar.copy")
;;             :on-click on-toggle-copy}
;;            (when (:show-copy-tooltip @local)
;;              [:& grid-options-tooltip {:selected id
;;                                        :title (tr "ds.multiselect-bar.copy-to-library")
;;                                        :on-select on-copy}])
;;            i/organize]])])))
;; 
;; (mf/defc grid-item
;;   [{:keys [color selected?] :as props}]
;;   (letfn [(toggle-selection [event]
;;             (st/emit! (dc/toggle-color-selection color)))]
;;     [:div.grid-item.small-item.project-th {:on-click toggle-selection}
;;      [:span.color-swatch {:style {:background-color color}}]
;;      [:div.input-checkbox.check-primary
;;       [:input {:type "checkbox"
;;                :id color
;;                :on-change toggle-selection
;;                :checked selected?}]
;;       [:label {:for color}]]
;;      [:span.color-data color]
;;      [:span.color-data (apply str "RGB " (interpose ", " (hex->rgb color)))]]))
;; 
;; (mf/defc grid
;;   [{:keys [id type coll selected] :as props}]
;;   (let [{:keys [colors]} coll
;;         editable? (= :own type)
;;         colors (->> (remove nil? colors)
;;                     (sort-by identity))]
;;     [:div.dashboard-grid-content
;;      [:div.dashboard-grid-row
;;       (when (and editable? id)
;;         [:& grid-form {:id id}])
;;       (for [color colors]
;;         (let [selected? (contains? selected color)]
;;           [:& grid-item {:color color :selected? selected? :key color}]))]]))
;; 
;; (mf/defc content
;;   [{:keys [id type coll] :as props}]
;;   (let [selected (mf/deref selected-colors-iref)]
;;     [:section.dashboard-grid.library
;;      (when coll
;;        [:& grid-header {:coll coll}])
;;      [:& grid {:coll coll :id id  :type type :selected selected}]
;;      (when (seq selected)
;;        [:& grid-options {:id id :type type
;;                          :selected selected
;;                          :coll coll}])]))
;; 
;; ;; --- Colors Page
;; 
;; (mf/defc colors-page
;;   [{:keys [id type] :as props}]
;;   (let [type (or type :own)
;; 
;;         colls (mf/deref collections-iref)
;;         colls (cond->> (vals colls)
;;                 (= type :own) (filter #(= :own (:type %)))
;;                 (= type :builtin) (filter #(= :builtin (:type %)))
;;                 true (sort-by :created-at))
;;         selected-coll (if id
;;                         (seek #(= id (:id %)) colls)
;;                         (first colls))
;;         id (:id selected-coll)]
;; 
;;     (mf/use-effect #(st/emit! (dc/fetch-collections)))
;; 
;;     [:section.dashboard-content
;;      [:& nav {:type type
;;               :id id
;;               :colls colls}]
;;      [:& content {:type type
;;                   :id id
;;                   :coll selected-coll}]]))
;; 
