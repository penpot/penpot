;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.dashboard.icons
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.icons :as di]
   [uxbox.main.store :as st]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.dashboard.common :as common]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.util.components :refer [chunked-list]]
   [uxbox.util.data :refer [read-string jscoll->vec seek]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as t :refer [tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.ordering.by-name"
   :created "ds.ordering.by-creation-date"})

(defn- sort-icons-by
  [ordering icons]
  (case ordering
    :name (sort-by :name icons)
    :created (reverse (sort-by :created-at icons))
    icons))

(defn- contains-term?
  [phrase term]
  {:pre [(string? phrase)
         (string? term)]}
  (let [term (name term)]
    (str/includes? (str/lower phrase) (str/trim (str/lower term)))))

(defn- filter-icons-by
  [term icons]
  (if (str/blank? term)
    icons
    (filter #(contains-term? (:name %) term) icons)))

;; --- Refs

(def collections-iref
  (-> (l/key :icons-collections)
      (l/derive st/state)))

(def opts-iref
  (-> (l/in [:dashboard :icons])
      (l/derive st/state)))

;; --- Page Title

(mf/defc grid-header
  [{:keys [coll] :as props}]
  (letfn [(on-change [name]
            (st/emit! (di/rename-collection (:id coll) name)))
          (delete []
            (st/emit!
             (di/delete-collection (:id coll))
             (rt/nav :dashboard/icons nil {:type (:type coll)})))
          (on-delete []
            (modal/show! confirm-dialog {:on-accept delete}))]
    [:& common/grid-header {:value (:name coll)
                            :on-change on-change
                            :on-delete on-delete}]))

;; --- Nav

(defn- make-num-icons-iref
  [id]
  (letfn [(selector [icons]
            (->> (vals icons)
                 (filter #(= id (:collection %)))
                 (count)))]
    (-> (comp (l/key :icons)
              (l/lens selector))
        (l/derive st/state))))

(mf/defc nav-item
  [{:keys [coll selected?] :as props}]
  (let [local (mf/use-state {})
        {:keys [id type name num-icons]} coll
        ;; TODO: recalculate the num-icons on crud operations for
        ;; avod doing this on UI.
        ;; num-icons-iref (mf/use-memo {:deps #js [id]
        ;;                              :fn #(make-num-icons-iref (:id coll))})
        ;; num-icons (mf/deref num-icons-iref)
        editable? (= type :own)]
    (letfn [(on-click [event]
              (let [type (or type :own)]
                (st/emit! (rt/nav :dashboard/icons {} {:type type :id id}))))
            (on-input-change [event]
              (-> (dom/get-target event)
                  (dom/get-value)
                  (swap! local assoc :name)))
          (on-cancel [event]
            (swap! local dissoc :name :edit))
          (on-double-click [event]
            (when editable?
              (swap! local assoc :edit true)))
          (on-input-keyup [event]
            (when (kbd/enter? event)
              (let [value (-> (dom/get-target event) (dom/get-value))]
                (st/emit! (di/rename-collection id (str/trim (:name @local))))
                (swap! local assoc :edit false))))]
      [:li {:on-click on-click
            :on-double-click on-double-click
            :class-name (when selected? "current")}
       (if (:edit @local)
         [:div
          [:input.element-title {:value (if (:name @local)
                                          (:name @local)
                                          (if id name "Storage"))
                                 :on-change on-input-change
                                 :on-key-down on-input-keyup}]
          [:span.close {:on-click on-cancel} i/close]]
         [:span.element-title (if id name "Storage")])
       [:span.element-subtitle (tr "ds.num-elements" (t/c num-icons))]])))


(mf/defc nav
  [{:keys [id type colls selected-coll] :as props}]
  (let [own? (= type :own)
        builtin? (= type :builtin)
        select-tab #(st/emit! (rt/nav :dashboard/icons nil {:type %}))]
    [:div.library-bar
     [:div.library-bar-inside
      [:ul.library-tabs
       [:li {:class-name (when own? "current")
             :on-click (partial select-tab :own)}
        (tr "ds.your-icons-title")]
       [:li {:class-name (when builtin? "current")
             :on-click (partial select-tab :builtin)}
        (tr "ds.store-icons-title")]]

      [:ul.library-elements
       (when own?
         [:li
          [:a.btn-primary {:on-click #(st/emit! (di/create-collection))}
           (tr "ds.icons-collection.new")]])
       (when own?
         [:& nav-item {:selected? (nil? id)}])
       (for [item colls]
         [:& nav-item {:coll item
                       :selected? (= (:id item) id)
                       :key (:id item)}])]]]))


;; --- Grid

(mf/defc grid-form
  [{:keys [id type uploading?] :as props}]
  (let [input (mf/use-ref nil)
        on-click #(dom/click (mf/ref-node input))
        on-select #(st/emit! (->> (dom/get-event-files %)
                                  (jscoll->vec)
                                  (di/create-icons id)))]
    [:div.grid-item.add-project {:on-click on-click}
     (if uploading?
       [:div i/loader-pencil]
       [:span (tr "ds.icon-new")])
     [:input.upload-icon-input
      {:style {:display "none"}
       :multiple true
       :ref input
       :value ""
       :accept "icon/svg+xml"
       :type "file"
       :on-change on-select}]]))

(mf/def grid-options-tooltip
  :mixins [mf/reactive mf/memo]

  :render
  (fn [own {:keys [selected on-select title]}]
    {:pre [(uuid? selected)
           (fn? on-select)
           (string? title)]}
    (let [colls (mf/react collections-iref)
          colls (->> (vals colls)
                     (filter #(= :own (:type %)))
                     (remove #(= selected (:id %)))
                     (sort-by :name colls))
          on-select (fn [event id]
                      (dom/prevent-default event)
                      (dom/stop-propagation event)
                      (on-select id))]
      [:ul.move-list
       [:li.title title]
       [:li
        [:a {:href "#" :on-click #(on-select % nil)} "Storage"]]
       (for [{:keys [id name] :as coll} colls]
         [:li {:key (pr-str id)}
          [:a {:on-click #(on-select % id)} name]])])))

(mf/def grid-options
  :mixins [(mf/local) mf/memo]

  :render
  (fn [{:keys [::mf/local] :as own}
       {:keys [id type selected] :as props}]
    (letfn [(delete []
              (st/emit! (di/delete-selected)))
            (on-delete [event]
              (modal/show! confirm-dialog {:on-accept delete}))
            (on-toggle-copy [event]
              (swap! local update :show-copy-tooltip not))
            (on-toggle-move [event]
              (swap! local update :show-move-tooltip not))
            (on-copy [selected]
              (swap! local assoc
                     :show-move-tooltip false
                     :show-copy-tooltip false)
              (st/emit! (di/copy-selected selected)))
            (on-move [selected]
              (swap! local assoc
                     :show-move-tooltip false
                     :show-copy-tooltip false)
              (st/emit! (di/move-selected selected)))
            (on-rename [event]
              (let [selected (first selected)]
                (st/emit! (di/update-opts :edition selected))))]

      ;; MULTISELECT OPTIONS BAR
      [:div.multiselect-bar
       (if (or (= type :own) (nil? id))
         ;; if editable
         [:div.multiselect-nav {}
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.copy")
            :on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.copy-to-library")
                                    :on-select on-copy}))
           i/copy]
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.move")
            :on-click on-toggle-move}
           (when (:show-move-tooltip @local)
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.move-to-library")
                                    :on-select on-move}))
           i/move]
          (when (= 1 (count selected))
            [:span.move-item.tooltip.tooltip-top
             {:alt (tr "ds.multiselect-bar.rename")
              :on-click on-rename}
             i/pencil])
          [:span.delete.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.delete")
            :on-click on-delete}
           i/trash]]

         ;; if not editable
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.copy")
            :on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.copy-to-library")
                                    :on-select on-copy}))
           i/organize]])])))

;; --- Grid Item

(mf/defc grid-item
  [{:keys [icon selected? edition?] :as props}]
  (letfn [(toggle-selection [event]
            (st/emit! (di/toggle-icon-selection (:id icon))))
          (on-key-down [event]
            (when (kbd/enter? event)
              (on-blur event)))
          (on-blur [event]
            (let [target (dom/event->target event)
                  name (dom/get-value target)]
              (st/emit! (di/update-opts :edition false)
                        (di/rename-icon (:id icon) name))))
          (ignore-click [event]
            (dom/stop-propagation event)
            (dom/prevent-default event))
          (on-edit [event]
            (dom/stop-propagation event)
            (dom/prevent-default event)
            (st/emit! (di/update-opts :edition (:id icon))))]
    [:div.grid-item.small-item.project-th
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id (:id icon)
               :on-change toggle-selection
               :checked selected?}]
      [:label {:for (:id icon)}]]
     [:span.grid-item-icon
      [:& icon/icon-svg {:shape icon}]]
     [:div.item-info {:on-click ignore-click}
      (if edition?
        [:input.element-name {:type "text"
                              :auto-focus true
                              :on-key-down on-key-down
                              :on-blur on-blur
                              :on-click on-edit
                              :default-value (:name icon)}]
        [:h3 {:on-double-click on-edit}
         (:name icon)])
      (str (tr "ds.uploaded-at" (dt/format (:created-at icon) "DD/MM/YYYY")))]]))

;; --- Grid

(defn- make-icons-iref
  [id]
  (-> (comp (l/key :icons)
            (l/lens (fn [icons]
                      (->> (vals icons)
                           (filter #(= id (:collection %)))))))
      (l/derive st/state)))

(mf/defc grid
  [{:keys [id type coll opts] :as props}]
  (let [editable? (or (= type :own) (nil? id))
        icons-iref (mf/use-memo #(make-icons-iref id) #js [id])
        icons (->> (mf/deref icons-iref)
                   (filter-icons-by (:filter opts ""))
                   (sort-icons-by (:order opts :name)))]
    [:div.dashboard-grid-content
     [:div.dashboard-grid-row
      (when editable?
        [:& grid-form {:id id :type type :uploading? (:uploading opts)}])

      [:& chunked-list {:items icons
                        :initial-size 30
                        :chunk-size 30
                        :key (str type id (count icons))}
       (fn [icon]
         [:& grid-item {:icon icon
                        :key (:id icon)
                        :selected (contains? (:selected opts) (:id icon))
                        :edition? (= (:edition opts) (:id icon))}])]]]))

;; --- Menu

(mf/defc menu
  [{:keys [opts coll] :as props}]
  (let [ordering (:order opts :name)
        filtering (:filter opts "")
        icount (count (:icons coll))]
    (letfn [(on-term-change [event]
              (let [term (-> (dom/get-target event)
                             (dom/get-value))]
                (st/emit! (di/update-opts :filter term))))
            (on-ordering-change [event]
              (let [value (dom/event->value event)
                    value (read-string value)]
                (st/emit! (di/update-opts :order value))))
            (on-clear [event]
              (st/emit! (di/update-opts :filter "")))]
      [:section.dashboard-bar.library-gap
       [:div.dashboard-info

        ;; Counter
        [:span.dashboard-icons (tr "ds.num-icons" (t/c icount))]

        ;; Sorting
        [:div
         [:span (tr "ds.ordering")]
         [:select.input-select {:on-change on-ordering-change
                                :value (pr-str ordering)}
          (for [[key value] (seq +ordering-options+)]
            [:option {:key key :value (pr-str key)} (tr value)])]]

        ;; Search
        [:form.dashboard-search
         [:input.input-text {:key :icons-search-box
                             :type "text"
                             :on-change on-term-change
                             :auto-focus true
                             :placeholder (tr "ds.search.placeholder")
                             :value filtering}]
         [:div.clear-search {:on-click on-clear} i/close]]]])))

;; --- Content

(mf/defc content
  [{:keys [id type coll] :as props}]
  (let [opts (mf/deref opts-iref)]
    [:*
     [:& menu {:opts opts :coll coll}]
     [:section.dashboard-grid.library
      (when coll
        [:& grid-header {:coll coll}])
      [:& grid {:id id
                :key [id type]
                :type type
                :coll coll
                :opts opts}]
      (when (seq (:selected opts))
        [:& grid-options {:id id :type type :selected (:selected opts)}])]]))

;; --- Icons Page

(mf/defc icons-page
  [{:keys [id type] :as props}]
  (let [type (or type :own)
        colls (mf/deref collections-iref)
        colls (cond->> (vals colls)
                (= type :own) (filter #(= :own (:type %)))
                (= type :builtin) (filter #(= :builtin (:type %)))
                true (sort-by :created-at))
        selected-coll (cond
                        (and (= type :own) (nil? id)) nil
                        (uuid? id) (seek #(= id (:id %)) colls)
                        :else (first colls))
        id (:id selected-coll)]

    (mf/use-effect #(st/emit! (di/fetch-collections)))
    (mf/use-effect {:fn #(st/emit! (di/initialize)
                                   (di/fetch-icons id))
                    :deps #js [id type]})

    [:section.dashboard-content
     [:& nav {:type type
              :id id
              :colls colls
              :selected-coll selected-coll}]
     [:& content {:type type
                  :id id
                  :coll selected-coll}]]))
