;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.images
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.images :as di]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.store :as st]
   [uxbox.main.ui.dashboard.common :as common]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.lightbox :as lbx]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.data :refer [read-string jscoll->vec seek]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as t :refer [tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.ordering.by-name"
   :created "ds.ordering.by-creation-date"})

(defn- sort-images-by
  [ordering images]
  (case ordering
    :name (sort-by :name images)
    :created (reverse (sort-by :created-at images))
    images))

(defn- contains-term?
  [phrase term]
  (let [term (name term)]
    (str/includes? (str/lower phrase) (str/trim (str/lower term)))))

(defn- filter-images-by
  [term images]
  (if (str/blank? term)
    images
    (filter #(contains-term? (:name %) term) images)))

;; --- Refs

(def collections-iref
  (-> (l/key :images-collections)
      (l/derive st/state)))

(def opts-iref
  (-> (l/in [:dashboard :images])
      (l/derive st/state)))

;; --- Page Title

(mf/defc grid-header
  [{:keys [coll] :as props}]
  (letfn [(on-change [name]
            (st/emit! (di/rename-collection (:id coll) name)))

          (delete []
            (st/emit!
             (di/delete-collection (:id coll))
             (rt/nav :dashboard/images nil {:type (:type coll)})))

          (on-delete []
            (modal/show! confirm-dialog {:on-accept delete}))]
    [:& common/grid-header {:value (:name coll)
                            :on-change on-change
                            :on-delete on-delete}]))

;; --- Nav

(defn- make-num-images-iref
  [id]
  (letfn [(selector [images]
            (->> (vals images)
                 (filter #(= id (:collection-id %)))
                 (count)))]
    (-> (comp (l/key :images)
              (l/lens selector))
        (l/derive st/state))))

(mf/defc nav-item
  [{:keys [coll selected?] :as props}]
  (let [local (mf/use-state {})
        {:keys [id type name num-images]} coll
        ;; TODO: recalculate the num-images on crud operations for
        ;; avod doing this on UI.
        num-images-iref (mf/use-memo #(make-num-images-iref (:id coll)) #js [id])
        num-images (mf/deref num-images-iref)
        editable? (= type :own)]
    (letfn [(on-click [event]
              (let [type (or type :own)]
                (st/emit! (rt/nav :dashboard/images {} {:type type :id id}))))
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
       [:span.element-subtitle (tr "ds.num-elements" (t/c num-images))]])))

(mf/defc nav
  [{:keys [id type colls selected-coll] :as props}]
  (let [own? (= type :own)
        builtin? (= type :builtin)
        select-tab #(st/emit! (rt/nav :dashboard/images nil {:type %}))]
    [:div.library-bar
     [:div.library-bar-inside
      [:ul.library-tabs
       [:li {:class-name (when own? "current")
             :on-click (partial select-tab :own)}
        (tr "ds.your-images-title")]
       [:li {:class-name (when builtin? "current")
             :on-click (partial select-tab :builtin)}
        (tr "ds.store-images-title")]]

      [:ul.library-elements
       (when own?
         [:li
          [:a.btn-primary {:on-click #(st/emit! (di/create-collection))}
           (tr "ds.images-collection.new")]])
       (when own?
         [:& nav-item {:selected? (nil? id)}])
       (for [item colls]
         [:& nav-item {:coll item
                       :selected? (= (:id item) id)
                       :key (:id item)}])]]]))

;; --- Grid

(mf/defc grid-options-tooltip
  [{:keys [selected on-select title] :as props}]
  {:pre [(uuid? selected)
         (fn? on-select)
         (string? title)]}
  (let [colls (mf/deref collections-iref)
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
      (when (not (nil? selected))
        [:a {:href "#" :on-click #(on-select % nil)} "Storage"])]
     (for [{:keys [id name] :as coll} colls]
       [:li {:key (pr-str id)}
        [:a {:on-click #(on-select % id)} name]])]))

(mf/defc grid-options
  [{:keys [id type selected] :as props}]
  (let [local (mf/use-state {})]
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
         ;; If editable
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.copy")
            :on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             [:& grid-options-tooltip {:selected id
                                       :title (tr "ds.multiselect-bar.copy-to-library")
                                       :on-select on-copy}])
           i/copy]
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.move")
            :on-click on-toggle-move}
           (when (:show-move-tooltip @local)
             [:& grid-options-tooltip {:selected id
                                       :title (tr "ds.multiselect-bar.move-to-library")
                                       :on-select on-move}])
           i/move]
          (when (= 1 (count selected))
            [:span.move-item.tooltip.tooltip-top {:alt (tr "ds.multiselect-bar.rename")
                                                  :on-click on-rename}
             i/pencil])
          [:span.delete.tooltip.tooltip-top {:alt (tr "ds.multiselect-bar.delete")
                                             :on-click on-delete}
           i/trash]]

         ;; If not editable
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top {:alt (tr "ds.multiselect-bar.copy")
                                                :on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             [:& grid-options-tooltip {:selected id
                                       :title (tr "ds.multiselect-bar.copy-to-library")
                                       :on-select on-copy}])
           i/organize]])])))

(mf/defc grid-item
  [{:keys [image selected? edition?] :as props}]
  (letfn [(toggle-selection [event]
            (st/emit! (di/toggle-image-selection (:id image))))
          (on-key-down [event]
            (when (kbd/enter? event)
              (on-blur event)))
          (on-blur [event]
            (let [target (dom/event->target event)
                  name (dom/get-value target)]
              (st/emit! (di/update-opts :edition false)
                        (di/rename-image (:id image) name))))
          (on-edit [event]
            (dom/stop-propagation event)
            (dom/prevent-default event)
            (st/emit! (di/update-opts :edition (:id image))))]
    [:div.grid-item.images-th
     [:div.grid-item-th {:style {:background-image (str "url('" (:thumbnail image) "')")}}
      [:div.input-checkbox.check-primary
       [:input {:type "checkbox"
                :id (:id image)
                :on-change toggle-selection
                :checked selected?}]
       [:label {:for (:id image)}]]]
     [:div.item-info
      (if edition?
        [:input.element-name {:type "text"
                              :auto-focus true
                              :on-key-down on-key-down
                              :on-blur on-blur
                              :on-click on-edit
                              :default-value (:name image)}]
        [:h3 {:on-double-click on-edit} (:name image)])
      [:span.date (str (tr "ds.uploaded-at"
                           (dt/format (:created-at image) "DD/MM/YYYY")))]]]))

;; --- Grid Form

(mf/defc grid-form
  [{:keys [id type uploading?] :as props}]
  (let [input (mf/use-ref nil)
        on-click #(dom/click (mf/ref-node input))
        on-select #(st/emit! (->> (dom/get-event-files %)
                                  (jscoll->vec)
                                  (di/create-images id)))]
    [:div.grid-item.add-project {:on-click on-click}
     (if uploading?
       [:div i/loader-pencil]
       [:span (tr "ds.image-new")])
     [:input.upload-image-input
      {:style {:display "none"}
       :multiple true
       :ref input
       :value ""
       :accept "image/jpeg,image/png"
       :type "file"
       :on-change on-select}]]))

;; --- Grid

(defn- make-images-iref
  [id]
  (-> (comp (l/key :images)
            (l/lens (fn [images]
                      (->> (vals images)
                           (filter #(= id (:collection-id %)))))))
      (l/derive st/state)))

(mf/defc grid
  [{:keys [id type coll opts] :as props}]
  (let [editable? (or (= type :own) (nil? id))
        images-iref (mf/use-memo #(make-images-iref id) #js [id])
        images (->> (mf/deref images-iref)
                    (filter-images-by (:filter opts ""))
                    (sort-images-by (:order opts :name)))]
    [:div.dashboard-grid-content
     [:div.dashboard-grid-row
      (when editable?
        [:& grid-form {:id id :type type :uploading? (:uploading opts)}])
      (for [item images]
        [:& grid-item {:image item
                       :key (:id item)
                       :selected? (contains? (:selected opts) (:id item))
                       :edition? (= (:edition opts) (:id item))}])]]))

;; --- Menu

(mf/defc menu
  [{:keys [opts coll] :as props}]
  (let [ordering (:order opts :name)
        filtering (:filter opts "")
        icount (count (:images coll))]
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
        [:span.dashboard-images (tr "ds.num-images" (t/c icount))]

        ;; Sorting
        [:div
         [:span (tr "ds.ordering")]
         [:select.input-select {:on-change on-ordering-change
                                :value (pr-str ordering)}
          (for [[key value] (seq +ordering-options+)]
            [:option {:key key :value (pr-str key)} (tr value)])]]

        ;; Search
        [:form.dashboard-search
         [:input.input-text {:key :images-search-box
                             :type "text"
                             :on-change on-term-change
                             :auto-focus true
                             :placeholder (tr "ds.search.placeholder")
                             :value filtering}]
         [:div.clear-search {:on-click on-clear} i/close]]]])))

(mf/defc content
  [{:keys [id type coll] :as props}]
  (let [opts (mf/deref opts-iref)]
    [:*
     [:& menu {:opts opts :coll coll}]
     [:section.dashboard-grid.library
      (when coll
        [:& grid-header {:coll coll}])
      [:& grid {:id id
                :type type
                :coll coll
                :opts opts}]
      (when (seq (:selected opts))
        [:& grid-options {:id id :type type :selected (:selected opts)}])]]))

;; --- Images Page

(mf/defc images-page
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
                                   (di/fetch-images id))
                    :deps #js [id type]})

    [:section.dashboard-content
     [:& nav {:type type
              :id id
              :colls colls
              :selected-coll selected-coll}]
     [:& content {:type type
                  :id id
                  :coll selected-coll}]]))
