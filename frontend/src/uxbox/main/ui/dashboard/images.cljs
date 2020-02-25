;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.images
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.data :as d]
   [uxbox.common.spec :as us]
   [uxbox.main.data.images :as di]
   [uxbox.main.store :as st]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.dashboard.common :as common]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; --- Page Title

(mf/defc grid-header
  [{:keys [collection] :as props}]
  (let [{:keys [id type]} collection
        on-change #(st/emit! (di/rename-collection id %))
        on-deleted #(st/emit! (rt/nav :dashboard-images nil {:type type}))
        delete #(st/emit! (di/delete-collection id on-deleted))
        on-delete #(modal/show! confirm-dialog {:on-accept delete})]
    [:& common/grid-header {:value (:name collection)
                            :on-change on-change
                            :on-delete on-delete}]))

;; --- Nav

(mf/defc nav-item
  [{:keys [coll selected?] :as props}]
  (let [local (mf/use-state {})
        {:keys [id type name num-images]} coll
        editable? (= type :own)

        on-click
        (fn [event]
          (let [type (or type :own)]
            (st/emit! (rt/nav :dashboard-images {} {:type type :id id}))))

        on-cancel-edition #(swap! local dissoc :edit)
        on-double-click #(when editable? (swap! local assoc :edit true))

        on-input-keyup
        (fn [event]
          (when (kbd/enter? event)
            (let [value (-> (dom/get-target event)
                            (dom/get-value)
                            (str/trim))]
              (st/emit! (di/rename-collection id value))
              (swap! local assoc :edit false))))]

    [:li {:on-click on-click
          :on-double-click on-double-click
          :class-name (when selected? "current")}
     (if (:edit @local)
       [:div
        [:input.element-title {:default-value name
                               :on-key-down on-input-keyup}]
        [:span.close {:on-click on-cancel-edition} i/close]]
       [:span.element-title (if id name "Storage")])]))

(mf/defc nav
  [{:keys [id type collections] :as props}]
  (let [locale (i18n/use-locale)
        own? (= type :own)
        builtin? (= type :builtin)
        create-collection #(st/emit! di/create-collection)
        select-own-tab #(st/emit! (rt/nav :dashboard-images nil {:type :own}))
        select-buitin-tab #(st/emit! (rt/nav :dashboard-images nil {:type :builtin}))]
    [:div.library-bar
     [:div.library-bar-inside

      ;; Tabs
      [:ul.library-tabs
       [:li {:class (when own? "current")
             :on-click select-own-tab}
        (t locale "ds.your-images-title")]

       [:li {:class (when builtin? "current")
             :on-click select-buitin-tab}
        (t locale "ds.store-images-title")]]

      ;; Collections List
      [:ul.library-elements
       (when own?
         [:li
          [:a.btn-primary {:on-click create-collection}
           (t locale "ds.images-collection.new")]])

       (for [item collections]
         [:& nav-item {:coll item
                       :selected? (= (:id item) id)
                       :key (:id item)}])]]]))

;; --- Grid

;; (mf/defc grid-options-tooltip
;;   [{:keys [selected on-select title] :as props}]
;;   {:pre [(uuid? selected)
;;          (fn? on-select)
;;          (string? title)]}
;;   (let [colls (mf/deref collections-iref)
;;         colls (->> (vals colls)
;;                    (filter #(= :own (:type %)))
;;                    (remove #(= selected (:id %)))
;;                    #_(sort-by :name colls))
;;         on-select (fn [event id]
;;                     (dom/prevent-default event)
;;                     (dom/stop-propagation event)
;;                     (on-select id))]
;;     [:ul.move-list
;;      [:li.title title]
;;      [:li
;;       (when (not (nil? selected))
;;         [:a {:href "#" :on-click #(on-select % nil)} "Storage"])]
;;      (for [{:keys [id name] :as coll} colls]
;;        [:li {:key (pr-str id)}
;;         [:a {:on-click #(on-select % id)} name]])]))

(mf/defc grid-options
  [{:keys [id type selected] :as props}]
  (let [local (mf/use-state {})
        delete #(st/emit! di/delete-selected)
        on-delete #(modal/show! confirm-dialog {:on-accept delete})

        ;; (on-toggle-copy [event]
        ;;                 (swap! local update :show-copy-tooltip not))
        ;;     (on-toggle-move [event]
        ;;       (swap! local update :show-move-tooltip not))
        ;;     (on-copy [selected]
        ;;       (swap! local assoc
        ;;              :show-move-tooltip false
        ;;              :show-copy-tooltip false)
        ;;       (st/emit! (di/copy-selected selected)))
        ;;     (on-move [selected]
        ;;       (swap! local assoc
        ;;              :show-move-tooltip false
        ;;              :show-copy-tooltip false)
        ;;       (st/emit! (di/move-selected selected)))
        ;; (on-rename [event]
        ;;   (let [selected (first selected)]
        ;;     (st/emit! (di/update-opts :edition selected))))
        ]
    ;; MULTISELECT OPTIONS BAR
    [:div.multiselect-bar
     (when (= type :own)
       ;; If editable
       [:div.multiselect-nav
        ;; [:span.move-item.tooltip.tooltip-top
        ;;  {:alt (tr "ds.multiselect-bar.copy")
        ;;   :on-click on-toggle-copy}
        ;;  (when (:show-copy-tooltip @local)
        ;;    [:& grid-options-tooltip {:selected id
        ;;                              :title (tr "ds.multiselect-bar.copy-to-library")
        ;;                              :on-select on-copy}])
        ;;  i/copy]
        ;; [:span.move-item.tooltip.tooltip-top
        ;;  {:alt (tr "ds.multiselect-bar.move")
        ;;   :on-click on-toggle-move}
        ;;  (when (:show-move-tooltip @local)
        ;;    [:& grid-options-tooltip {:selected id
        ;;                              :title (tr "ds.multiselect-bar.move-to-library")
        ;;                              :on-select on-move}])
        ;;  i/move]
        ;; (when (= 1 (count selected))
        ;;   [:span.move-item.tooltip.tooltip-top {:alt (tr "ds.multiselect-bar.rename")
        ;;                                         :on-click on-rename}
        ;;    i/pencil])
        [:span.delete.tooltip.tooltip-top
         {:alt (tr "ds.multiselect-bar.delete")
          :on-click on-delete}
         i/trash]]

       ;; If not editable
       ;; [:div.multiselect-nav
       ;;  [:span.move-item.tooltip.tooltip-top {:alt (tr "ds.multiselect-bar.copy")
       ;;                                        :on-click on-toggle-copy}
       ;;   (when (:show-copy-tooltip @local)
       ;;     [:& grid-options-tooltip {:selected id
       ;;                               :title (tr "ds.multiselect-bar.copy-to-library")
       ;;                               :on-select on-copy}])
       ;;   i/organize]]
       )]))


;; --- Grid Form

(mf/defc grid-form
  [{:keys [id type uploading?] :as props}]
  (let [input (mf/use-ref nil)
        on-click #(dom/click (mf/ref-node input))
        on-select #(st/emit! (->> (dom/get-target %)
                                  (dom/get-files)
                                  (array-seq)
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
       :accept "image/jpeg,image/png,image/webp"
       :type "file"
       :on-change on-select}]]))

;; --- Grid Item

(mf/defc grid-item
  [{:keys [image selected? edition?] :as props}]
  (let [toggle-selection #(st/emit! (if selected?
                                      (di/deselect-image (:id image))
                                      (di/select-image (:id image))))
        on-blur
        (fn [event]
          (let [target (dom/get-target event)
                name (dom/get-value target)]
            (st/emit! (di/update-opts :edition false)
                      (di/rename-image (:id image) name))))

        on-key-down
        (fn [event]
          (when (kbd/enter? event)
            (on-blur event)))

        on-edit
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)
          (st/emit! (di/update-opts :edition (:id image))))

        background (str "url('" (:thumb-uri image) "')")]

    [:div.grid-item.images-th
     [:div.grid-item-th {:style {:background-image background}}
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
      [:span.date (tr "ds.uploaded-at" (dt/format (:created-at image) "dd/MM/yyyy"))]]]))

;; --- Grid

;; (defn- make-images-iref
;;   [collection-id]
;;   (letfn [(selector [state]
;;             (->> (vals (:images state))
;;                  (filterv #(= (:collection-id %) collection-id))))]
;;     (-> (l/lens selector)
;;         (l/derive st/state))))

(def images-iref
  (-> (comp (l/key :images) (l/lens vals))
      (l/derive st/state)))

(mf/defc grid
  [{:keys [id type collection opts] :as props}]
  (let [editable? (= type :own)
        ;; images-iref (mf/use-memo {:fn #(make-images-iref id)
        ;;                           :deps (mf/deps id)})
        images (->> (mf/deref images-iref)
                    (sort-by :created-at))]
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

;; (mf/defc menu
;;   [{:keys [opts coll] :as props}]
;;   (let [ordering (:order opts :name)
;;         filtering (:filter opts "")
;;         icount (count (:images coll))]
;;     (letfn [(on-term-change [event]
;;               (let [term (-> (dom/get-target event)
;;                              (dom/get-value))]
;;                 (st/emit! (di/update-opts :filter term))))
;;             (on-ordering-change [event]
;;               (let [value (dom/event->value event)
;;                     value (read-string value)]
;;                 (st/emit! (di/update-opts :order value))))
;;             (on-clear [event]
;;               (st/emit! (di/update-opts :filter "")))]
;;       [:section.dashboard-bar.library-gap
;;        [:div.dashboard-info

;;         ;; Counter
;;         [:span.dashboard-images (tr "ds.num-images" (t/c icount))]

;;         ;; Sorting
;;         [:div
;;          [:span (tr "ds.ordering")]
;;          [:select.input-select {:on-change on-ordering-change
;;                                 :value (pr-str ordering)}
;;           (for [[key value] (seq +ordering-options+)]
;;             [:option {:key key :value (pr-str key)} (tr value)])]]

;;         ;; Search
;;         [:form.dashboard-search
;;          [:input.input-text {:key :images-search-box
;;                              :type "text"
;;                              :on-change on-term-change
;;                              :auto-focus true
;;                              :placeholder (tr "ds.search.placeholder")
;;                              :value filtering}]
;;          [:div.clear-search {:on-click on-clear} i/close]]]])))

(def opts-iref
  (-> (l/key :dashboard-images)
      (l/derive st/state)))

(mf/defc content
  [{:keys [id type collection] :as props}]
  (let [{:keys [selected] :as opts} (mf/deref opts-iref)]
    [:section.dashboard-grid.library
     (when collection
       [:& grid-header {:collection collection}])
     (if collection
       [:& grid {:id id :type type :collection collection :opts opts}]
       [:span "EMPTY STATE TODO"])
     (when-not (empty? selected)
       [:& grid-options {:id id :type type :selected selected}])]))

;; --- Images Page

(def collections-iref
  (-> (l/key :images-collections)
      (l/derive st/state)))

(mf/defc images-page
  [{:keys [id type] :as props}]
  (let [collections (mf/deref collections-iref)
        collections (cond->> (vals collections)
                      (= type :own) (filter #(= :own (:type %)))
                      (= type :builtin) (filter #(= :builtin (:type %)))
                      true (sort-by :created-at))

        collection (cond
                     (uuid? id) (d/seek #(= id (:id %)) collections)
                     :else (first collections))
        id (:id collection)]

    (mf/use-effect #(st/emit! di/fetch-collections))
    (mf/use-effect
     {:fn #(when id (st/emit! (di/initialize id)))
      :deps (mf/deps id)})

    [:section.dashboard-content
     [:& nav {:type type :id id :collections collections}]
     [:& content {:type type :id id :collection collection}]]))
