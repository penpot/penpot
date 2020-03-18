;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.dashboard.icons
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.common.spec :as us]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.icons :as di]
   [uxbox.main.store :as st]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.dashboard.common :as common]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.util.components :refer [chunked-list]]
   [uxbox.util.data :refer [read-string seek]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; ;; --- Helpers & Constants
;; 
;; (def +ordering-options+
;;   {:name "ds.ordering.by-name"
;;    :created "ds.ordering.by-creation-date"})
;; 
;; (defn- sort-icons-by
;;   [ordering icons]
;;   (case ordering
;;     :name (sort-by :name icons)
;;     :created (reverse (sort-by :created-at icons))
;;     icons))
;; 
;; (defn- contains-term?
;;   [phrase term]
;;   {:pre [(string? phrase)
;;          (string? term)]}
;;   (let [term (name term)]
;;     (str/includes? (str/lower phrase) (str/trim (str/lower term)))))
;; 
;; (defn- filter-icons-by
;;   [term icons]
;;   (if (str/blank? term)
;;     icons
;;     (filter #(contains-term? (:name %) term) icons)))
;; 
;; ;; --- Component: Grid Header
;; 
;; (mf/defc grid-header
;;   [{:keys [collection] :as props}]
;;   (let [{:keys [id type]} collection
;;         on-change #(st/emit! (di/rename-collection id %))
;;         on-deleted #(st/emit! (rt/nav :dashboard-icons nil {:type type}))
;;         delete #(st/emit! (di/delete-collection id on-deleted))
;;         on-delete #(modal/show! confirm-dialog {:on-accept delete})]
;;     [:& common/grid-header {:value (:name collection)
;;                             :on-change on-change
;;                             :on-delete on-delete}]))
;; 
;; ;; --- Nav
;; 
;; (mf/defc nav-item
;;   [{:keys [collection selected?] :as props}]
;;   (let [local (mf/use-state {})
;;         {:keys [id type name]} collection
;;         editable? (= type :own)
;; 
;;         on-click
;;         (fn [event]
;;           (let [type (or type :own)]
;;             (st/emit! (rt/nav :dashboard-icons {} {:type type :id id}))))
;; 
;; 
;;         on-input-change
;;         (fn [event]
;;           (-> (dom/get-target event)
;;               (dom/get-value)
;;               (swap! local assoc :name)))
;; 
;;         on-cancel #(swap! local dissoc :name :edit)
;;         on-double-click #(when editable? (swap! local assoc :edit true))
;; 
;;         on-input-keyup
;;         (fn [event]
;;           (when (kbd/enter? event)
;;             (let [value (-> (dom/get-target event) (dom/get-value))]
;;               (st/emit! (di/rename-collection id (str/trim (:name @local))))
;;               (swap! local assoc :edit false))))]
;; 
;;     [:li {:on-click on-click
;;           :on-double-click on-double-click
;;           :class-name (when selected? "current")}
;;      (if (:edit @local)
;;        [:div
;;         [:input.element-title {:value (or (:name @local) name)
;;                                :on-change on-input-change
;;                                :on-key-down on-input-keyup}]
;;         [:span.close {:on-click on-cancel} i/close]]
;;        [:span.element-title name])]))
;; 
;; 
;; (mf/defc nav
;;   [{:keys [id type collections] :as props}]
;;   (let [locale (i18n/use-locale)
;;         own? (= type :own)
;;         builtin? (= type :builtin)
;;         create-collection #(st/emit! di/create-collection)
;;         select-own-tab #(st/emit! (rt/nav :dashboard-icons nil {:type :own}))
;;         select-buitin-tab #(st/emit! (rt/nav :dashboard-icons nil {:type :builtin}))]
;; 
;;     [:div.library-bar
;;      [:div.library-bar-inside
;;       ;; Tabs
;;       [:ul.library-tabs
;;        [:li {:class (when own? "current")
;;              :on-click select-own-tab}
;;         (t locale "ds.your-icons-title")]
;; 
;;        [:li {:class (when builtin? "current")
;;              :on-click select-buitin-tab}
;;         (t locale "ds.store-icons-title")]]
;; 
;; 
;;       ;; Collections List
;;       [:ul.library-elements
;;        (when own?
;;          [:li
;;           [:a.btn-primary {:on-click #(st/emit! di/create-collection)}
;;            (tr "ds.icons-collection.new")]])
;;        (for [item collections]
;;          [:& nav-item {:collection item
;;                        :selected? (= (:id item) id)
;;                        :key (:id item)}])]]]))
;; 
;; 
;; ;; (mf/def grid-options-tooltip
;; ;;   :mixins [mf/reactive mf/memo]
;; 
;; ;;   :render
;; ;;   (fn [own {:keys [selected on-select title]}]
;; ;;     {:pre [(uuid? selected)
;; ;;            (fn? on-select)
;; ;;            (string? title)]}
;; ;;     (let [colls (mf/react collections-iref)
;; ;;           colls (->> (vals colls)
;; ;;                      (filter #(= :own (:type %)))
;; ;;                      (remove #(= selected (:id %)))
;; ;;                      (sort-by :name colls))
;; ;;           on-select (fn [event id]
;; ;;                       (dom/prevent-default event)
;; ;;                       (dom/stop-propagation event)
;; ;;                       (on-select id))]
;; ;;       [:ul.move-list
;; ;;        [:li.title title]
;; ;;        [:li
;; ;;         [:a {:href "#" :on-click #(on-select % nil)} "Storage"]]
;; ;;        (for [{:keys [id name] :as coll} colls]
;; ;;          [:li {:key (pr-str id)}
;; ;;           [:a {:on-click #(on-select % id)} name]])])))
;; 
;; (mf/defc grid-options
;;   [{:keys [id type selected] :as props}]
;;   (let [local (mf/use-state {})
;;         delete #(st/emit! di/delete-selected)
;;         on-delete #(modal/show! confirm-dialog {:on-accept delete})
;; 
;;         ;; (on-toggle-copy [event]
;;         ;;                 (swap! local update :show-copy-tooltip not))
;;         ;;     (on-toggle-move [event]
;;         ;;       (swap! local update :show-move-tooltip not))
;;         ;;     (on-copy [selected]
;;         ;;       (swap! local assoc
;;         ;;              :show-move-tooltip false
;;         ;;              :show-copy-tooltip false)
;;         ;;       (st/emit! (di/copy-selected selected)))
;;         ;;     (on-move [selected]
;;         ;;       (swap! local assoc
;;         ;;              :show-move-tooltip false
;;         ;;              :show-copy-tooltip false)
;;         ;;       (st/emit! (di/move-selected selected)))
;;         ;; (on-rename [event]
;;         ;;   (let [selected (first selected)]
;;         ;;     (st/emit! (di/update-opts :edition selected))))
;;         ]
;;     ;; MULTISELECT OPTIONS BAR
;;     [:div.multiselect-bar
;;      (when (= type :own)
;;        ;; If editable
;;        [:div.multiselect-nav
;;         ;; [:span.move-item.tooltip.tooltip-top
;;         ;;  {:alt (tr "ds.multiselect-bar.copy")
;;         ;;   :on-click on-toggle-copy}
;;         ;;  (when (:show-copy-tooltip @local)
;;         ;;    [:& grid-options-tooltip {:selected id
;;         ;;                              :title (tr "ds.multiselect-bar.copy-to-library")
;;         ;;                              :on-select on-copy}])
;;         ;;  i/copy]
;;         ;; [:span.move-item.tooltip.tooltip-top
;;         ;;  {:alt (tr "ds.multiselect-bar.move")
;;         ;;   :on-click on-toggle-move}
;;         ;;  (when (:show-move-tooltip @local)
;;         ;;    [:& grid-options-tooltip {:selected id
;;         ;;                              :title (tr "ds.multiselect-bar.move-to-library")
;;         ;;                              :on-select on-move}])
;;         ;;  i/move]
;;         ;; (when (= 1 (count selected))
;;         ;;   [:span.move-item.tooltip.tooltip-top {:alt (tr "ds.multiselect-bar.rename")
;;         ;;                                         :on-click on-rename}
;;         ;;    i/pencil])
;;         [:span.delete.tooltip.tooltip-top
;;          {:alt (tr "ds.multiselect-bar.delete")
;;           :on-click on-delete}
;;          i/trash]]
;; 
;;        ;; If not editable
;;        ;; [:div.multiselect-nav
;;        ;;  [:span.move-item.tooltip.tooltip-top {:alt (tr "ds.multiselect-bar.copy")
;;        ;;                                        :on-click on-toggle-copy}
;;        ;;   (when (:show-copy-tooltip @local)
;;        ;;     [:& grid-options-tooltip {:selected id
;;        ;;                               :title (tr "ds.multiselect-bar.copy-to-library")
;;        ;;                               :on-select on-copy}])
;;        ;;   i/organize]]
;;        )]))
;; 
;; ;; --- Grid Form
;; 
;; (mf/defc grid-form
;;   [{:keys [id type uploading?] :as props}]
;;   (let [locale (i18n/use-locale)
;;         input (mf/use-ref nil)
;;         on-click #(dom/click (mf/ref-node input))
;;         on-select #(st/emit! (->> (dom/get-target %)
;;                                   (dom/get-files)
;;                                   (array-seq)
;;                                   (di/create-icons id)))]
;;     [:div.grid-item.add-project {:on-click on-click}
;;      (if uploading?
;;        [:div i/loader-pencil]
;;        [:span (t locale "ds.icon-new")])
;;      [:input.upload-icon-input
;;       {:style {:display "none"}
;;        :multiple true
;;        :ref input
;;        :value ""
;;        :accept "icon/svg+xml"
;;        :type "file"
;;        :on-change on-select}]]))
;; 
;; ;; --- Grid Item
;; 
;; (mf/defc grid-item
;;   [{:keys [icon selected? edition?] :as props}]
;;   (let [toggle-selection #(st/emit! (if selected?
;;                                       (di/deselect-icon (:id icon))
;;                                       (di/select-icon (:id icon))))
;;         on-blur
;;         (fn [event]
;;           (let [target (dom/get-target event)
;;                 name (dom/get-value target)]
;;             (st/emit! (di/update-opts :edition false)
;;                       (di/rename-icon (:id icon) name))))
;; 
;;         on-key-down
;;         (fn [event]
;;           (when (kbd/enter? event)
;;             (on-blur event)))
;; 
;;         ignore-click
;;         (fn [event]
;;           (dom/stop-propagation event)
;;           (dom/prevent-default event))
;; 
;;         on-edit
;;         (fn [event]
;;           (dom/stop-propagation event)
;;           (dom/prevent-default event)
;;           (st/emit! (di/update-opts :edition (:id icon))))]
;; 
;;     [:div.grid-item.small-item.project-th
;;      [:div.input-checkbox.check-primary
;;       [:input {:type "checkbox"
;;                :id (:id icon)
;;                :on-change toggle-selection
;;                :checked selected?}]
;;       [:label {:for (:id icon)}]]
;;      [:span.grid-item-icon
;;       [:& icon/icon-svg {:shape icon}]]
;;      [:div.item-info {:on-click ignore-click}
;;       (if edition?
;;         [:input.element-name {:type "text"
;;                               :auto-focus true
;;                               :on-key-down on-key-down
;;                               :on-blur on-blur
;;                               :on-click on-edit
;;                               :default-value (:name icon)}]
;;         [:h3 {:on-double-click on-edit}
;;          (:name icon)])
;;       (str (tr "ds.uploaded-at" (dt/format (:created-at icon) "dd/MM/yyyy")))]]))
;; 
;; ;; --- Grid
;; 
;; (def icons-iref
;;   (-> (comp (l/key :icons) (l/lens vals))
;;       (l/derive st/state)))
;; 
;; (mf/defc grid
;;   [{:keys [id type collection opts] :as props}]
;;   (let [editable?  (= type :own)
;;         icons (->> (mf/deref icons-iref)
;;                    (filter-icons-by (:filter opts ""))
;;                    (sort-icons-by (:order opts :name)))]
;;     [:div.dashboard-grid-content
;;      [:div.dashboard-grid-row
;;       (when editable?
;;         [:& grid-form {:id id :type type :uploading? (:uploading opts)}])
;; 
;;       [:& chunked-list {:items icons
;;                         :initial-size 30
;;                         :chunk-size 30
;;                         :key (str type id (count icons))}
;;        (fn [icon]
;;          [:& grid-item {:icon icon
;;                         :key (:id icon)
;;                         :selected (contains? (:selected opts) (:id icon))
;;                         :edition? (= (:edition opts) (:id icon))}])]]]))
;; 
;; ;; --- Content
;; 
;; (def opts-iref
;;   (-> (l/key :dashboard-icons)
;;       (l/derive st/state)))
;; 
;; (mf/defc content
;;   [{:keys [id type collection] :as props}]
;;   (let [{:keys [selected] :as opts} (mf/deref opts-iref)]
;;     [:section.dashboard-grid.library
;;      (when collection
;;        [:& grid-header {:collection collection}])
;;      (if collection
;;        [:& grid {:id id :type type :collection collection :opts opts}]
;;        [:span "EMPTY STATE TODO"])
;;      (when-not (empty? selected)
;;        #_[:& grid-options {:id id :type type :selected (:selected opts)}])]))
;; 
;; ;; --- Icons Page
;; 
;; (def collections-iref
;;   (-> (l/key :icons-collections)
;;       (l/derive st/state)))
;; 
;; (mf/defc icons-page
;;   [{:keys [id type] :as props}]
;;   (let [type (or type :own)
;;         collections (mf/deref collections-iref)
;;         collections (cond->> (vals collections)
;;                       (= type :own) (filter #(= :own (:type %)))
;;                       (= type :builtin) (filter #(= :builtin (:type %)))
;;                       true (sort-by :created-at))
;; 
;;         collection (cond
;;                      (uuid? id) (seek #(= id (:id %)) collections)
;;                      :else (first collections))
;; 
;;         id (:id collection)]
;; 
;;     (mf/use-effect #(st/emit! di/fetch-collections))
;;     (mf/use-effect
;;      {:fn #(when id (st/emit! (di/initialize id)))
;;       :deps (mf/deps id)})
;; 
;;     [:section.dashboard-content
;;      [:& nav {:type type :id id :collections collections}]
;;      [:& content {:type type :id id :collection collection}]]))
