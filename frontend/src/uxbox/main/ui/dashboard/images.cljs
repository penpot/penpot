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
   [rumext.core :as mx :include-macros true]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.images :as di]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.store :as st]
   [uxbox.main.ui.dashboard.header :refer [header]]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.lightbox :as lbx]
   [uxbox.util.data :refer [read-string jscoll->vec]]
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

(def collections-ref
  (-> (l/key :images-collections)
      (l/derive st/state)))

(def opts-ref
  (-> (l/in [:dashboard :images])
      (l/derive st/state)))

;; --- Page Title

(mx/def page-title
  :mixins [(mx/local) mx/reactive]

  :render
  (fn [{:keys [::mx/local] :as own}
       {:keys [id type] :as coll}]
    (let [own? (= :own (:type coll))
          edit? (:edit @local)]
      (letfn [(on-save [e]
                (let [dom (mx/ref-node own "input")
                      name (dom/get-inner-text dom)]
                  (st/emit! (di/rename-collection id (str/trim name)))
                  (swap! local assoc :edit false)))
              (on-cancel [e]
                (swap! local assoc :edit false))
              (on-edit [e]
                (swap! local assoc :edit true))
              (on-input-keydown [e]
                (cond
                  (kbd/esc? e) (on-cancel e)
                  (kbd/enter? e)
                  (do
                    (dom/prevent-default e)
                    (dom/stop-propagation e)
                    (on-save e))))
              (delete []
                (st/emit! (di/delete-collection id)))
              (on-delete []
                (udl/open! :confirm {:on-accept delete}))]
        [:div.dashboard-title
         [:h2
          (if edit?
            [:div.dashboard-title-field
             [:span.edit
              {:content-editable true
               :ref "input"
               :on-key-down on-input-keydown}
              (:name coll)]
             [:span.close {:on-click on-cancel} i/close]]
            (if own?
              [:span.dashboard-title-field
               {:on-double-click on-edit}
               (:name coll)]
              [:span.dashboard-title-field
               (:name coll "Storage")]))]
         (when (and own? coll)
           [:div.edition
            (if edit?
              [:span {:on-click on-save} ^:inline i/save]
              [:span {:on-click on-edit} ^:inline i/pencil])
            [:span {:on-click on-delete} ^:inline i/trash]])]))))

;; --- Nav

(defn num-images-ref
  [id]
  (let [selector (fn [images] (count (filter #(= id (:collection %)) (vals images))))]
    (-> (comp (l/key :images)
              (l/lens selector))
        (l/derive st/state))))

(mx/def nav-item
  :key-fn :id
  :mixins [(mx/local) mx/static mx/reactive]

  :init
  (fn [own {:keys [id] :as props}]
    (assoc own ::num-images-ref (num-images-ref id)))

  :render
  (fn [{:keys [::mx/local] :as own}
       {:keys [id type name num-images selected?] :as coll}]
    (letfn [(on-click [event]
            (let [type (or type :own)]
              (st/emit! (rt/nav :dashboard/images {} {:type type :id id}))))
          (on-input-change [event]
            (let [value (dom/get-target event)
                  value (dom/get-value value)]
              (swap! local assoc :name value)))
          (on-cancel [event]
            (swap! local dissoc :name)
            (swap! local dissoc :edit))
          (on-double-click [event]
            (when (= type :own)
              (swap! local assoc :edit true)))
          (on-input-keyup [event]
            (when (kbd/enter? event)
              (let [value (dom/get-target event)
                    value (dom/get-value value)]
                (st/emit! (di/rename-collection id (str/trim (:name @local))))
                (swap! local assoc :edit false))))]
      [:li {:on-click on-click
            :on-double-click on-double-click
            :class-name (when selected? "current")}
       (if (:edit @local)
         [:div
          [:input.element-title
           {:value (if (:name @local) (:name @local) (if id name "Storage"))
            :on-change on-input-change
            :on-key-down on-input-keyup}]
          [:span.close {:on-click on-cancel} ^:inline i/close]]
         [:span.element-title {}
          (if id name "Storage")])
       [:span.element-subtitle
        (tr "ds.num-elements" (t/c (or num-images (mx/react (::num-images-ref own)))))]])))

(mx/def nav
  :mixins [mx/static mx/reactive]

  :render
  (fn [own {:keys [id type] :as props}]
    (let [own? (= type :own)
          builtin? (= type :builtin)
          colls (mx/react collections-ref)
          select-tab (fn [type]
                       (if-let [coll (->> (vals colls)
                                          (filter #(= type (:type %)))
                                          (sort-by :created-at)
                                          (first))]
                         (st/emit! (rt/nav :dashboard/images nil {:type type :id (:id coll)}))
                         (st/emit! (rt/nav :dashboard/images nil {:type type}))))]

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
           (nav-item {:selected? (nil? id)}))
         (for [coll (cond->> (vals colls)
                      own? (filter #(= :own (:type %)))
                      builtin? (filter #(= :builtin (:type %)))
                      own? (sort-by :name))]
           (let [selected? (= (:id coll) id)]
             (nav-item (assoc coll :selected? selected?))))]]])))

;; --- Grid

(mx/def grid-form
  :mixins #{mx/static}

  :init
  (fn [own props]
    (assoc own ::file-input (mx/create-ref)))

  :render
  (fn [own {:keys [id] :as props}]
    (letfn [(forward-click [event]
              (dom/click (mx/ref-node (::file-input own))))
            (on-file-selected [event]
              (let [files (dom/get-event-files event)
                    files (jscoll->vec files)]
                (st/emit! (di/create-images id files))))]
    (let [uploading? (:uploading props)]
      [:div.grid-item.add-project {:on-click forward-click}
       (if uploading?
         [:div i/loader-pencil]
         [:span (tr "ds.image-new")])
       [:input.upload-image-input
        {:style {:display "none"}
         :multiple true
         :ref (::file-input own)
         :value ""
         :accept "image/jpeg,image/png"
         :type "file"
         :on-change on-file-selected}]]))))

(mx/def grid-options-tooltip
  :mixins [mx/reactive mx/static]

  :render
  (fn [own {:keys [selected on-select title]}]
    {:pre [(uuid? selected)
           (fn? on-select)
           (string? title)]}
    (let [colls (mx/react collections-ref)
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

(mx/def grid-options
  :mixins [(mx/local)]

  :render
  (fn [{:keys [::mx/local] :as own}
       {:keys [id type selected] :as props}]
    (letfn [(delete []
              (st/emit! (di/delete-selected)))
            (on-delete [event]
              (udl/open! :confirm {:on-accept delete}))
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
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.copy-to-library")
                                    :on-select on-copy}))
           ^:inline i/copy]
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.move")
            :on-click on-toggle-move}
           (when (:show-move-tooltip @local)
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.move-to-library")
                                    :on-select on-move}))
           ^:inline i/move]
          (when (= 1 (count selected))
            [:span.move-item.tooltip.tooltip-top
             {:alt (tr "ds.multiselect-bar.rename")
              :on-click on-rename}
             ^:inline i/pencil])
          [:span.delete.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.delete")
            :on-click on-delete}
           ^:inline i/trash]]

         ;; If not editable
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.copy")
            :on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.copy-to-library")
                                    :on-select on-copy}))
           ^:inline i/organize]])])))

(mx/def grid-item
  :key-fn :id
  :mixins [mx/static]

  :render
  (fn [own {:keys [id created-at ::selected? ::edition?] :as image}]
    (letfn [(toggle-selection [event]
              (st/emit! (di/toggle-image-selection id)))
            (on-key-down [event]
              (when (kbd/enter? event)
                (on-blur event)))
            (on-blur [event]
              (let [target (dom/event->target event)
                    name (dom/get-value target)]
                (st/emit! (di/update-opts :edition false)
                          (di/rename-image id name))))
            (on-edit [event]
              (dom/stop-propagation event)
              (dom/prevent-default event)
              (st/emit! (di/update-opts :edition id)))]
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
        [:span.date (str (tr "ds.uploaded-at" (dt/format created-at "DD/MM/YYYY")))]]])))

(mx/def grid
  :mixins [mx/reactive]
  :init
  (fn [own {:keys [id] :as props}]
    (let [selector (fn [images]
                     (->> (vals images)
                          (filter #(= id (:collection %)))))]
      (assoc own ::images-ref (-> (comp (l/key :images)
                                        (l/lens selector))
                                  (l/derive st/state)))))

  :render
  (fn [own {:keys [selected edition id type] :as props}]
    (let [editable? (or (= type :own) (nil? id))
          images (->> (mx/react (::images-ref own))
                      (filter-images-by (:filter props ""))
                      (sort-images-by (:order props :name)))]
      [:div.dashboard-grid-content
       [:div.dashboard-grid-row
        (when editable?
          (grid-form props))
        (for [{:keys [id] :as image} images]
          (let [edition? (= edition id)
                selected? (contains? selected id)]
            (grid-item (assoc image ::selected? selected? ::edition? edition?))))]])))

;; --- Menu

(mx/def menu
  :mixins [mx/reactive mx/static]

  ;; :init
  ;; (fn [own {:keys [id] :as props}]
  ;;   (assoc own ::num-images-ref (num-images-ref id)))

  :render
  (fn [own props]
    (let [{:keys [id] :as coll} (::coll props)
          ordering (:order props :name)
          filtering (:filter props "")
          icount (count (:images coll))]
      (letfn [(on-term-change [event]
                (let [term (-> (dom/get-target event)
                               (dom/get-value))]
                  (prn "on-term-change" term)
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
           [:div.clear-search {:on-click on-clear}
            i/close]]]]))))

(mx/def content
  :mixins [mx/reactive mx/static]

  :init
  (fn [own {:keys [id] :as props}]
    (assoc own ::coll-ref (-> (l/in [:images-collections id])
                              (l/derive st/state))))

  :render
  (fn [own props]
    (let [opts (mx/react opts-ref)
          coll (mx/react (::coll-ref own))
          props (merge opts props)]
      [:*
       (menu (assoc props ::coll coll))
       [:section.dashboard-grid.library
        (page-title coll)
        (grid props)
        (when (seq (:selected opts))
          (grid-options props))]])))

;; --- Images Page

(mx/def images-page
  :key-fn identity
  :mixins #{mx/static mx/reactive}

  :init
  (fn [own props]
    (let [{:keys [type id]} (::mx/props own)]
      (st/emit! (di/initialize type id))
      own))

  :render
  (fn [own {:keys [type] :as props}]
    (let [type (or type :own)
          props (assoc props :type type)]
      [:section.dashboard-content
       (nav props)
       (content props)])))
